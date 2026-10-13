package dev.ericsanchez.ordersync.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Queue gauges (jobs and outbox messages by status, age of the oldest pending job) taken from Postgres, the source of
 * truth for the queue. A scrape never touches the database: a background task reads everything in one grouped query
 * every {@code ordersync.metrics.db-refresh-interval} and the gauges read that snapshot. So /actuator/prometheus
 * answers at once even while the database is down or the pool is exhausted, and the metrics that matter most during
 * an incident (circuit breakers, ERP/shop timers) are still scraped.
 * <p>
 * The refresh has its own one-connection pool with a short connection wait and statement timeout, so it can neither
 * hang nor take connections from the application. If it fails, the last snapshot stays and
 * {@code ordersync.db.snapshot.age} (seconds since the last successful refresh) shows how old it is.
 */
@Component
public class DatabaseGauges implements MeterBinder, SchedulingConfigurer, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(DatabaseGauges.class);

	private static final List<String> JOB_STATUSES = List.of("PENDING", "IN_PROGRESS", "RETRY", "DONE", "DEAD");

	private static final List<String> OUTBOX_STATUSES = List.of("PENDING", "IN_PROGRESS", "RETRY", "DELIVERED",
			"DEAD", "SUPERSEDED");

	/** Taken once per refresh; {@code refreshedAt} is null until the first refresh succeeds. */
	private record Snapshot(Map<String, Long> jobs, Map<String, Long> outbox, double oldestPendingAgeSeconds,
			Instant refreshedAt) {
	}

	private final HikariDataSource metricsDataSource;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final Duration refreshInterval;

	private final Instant startedAt;

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
			new Snapshot(Map.of(), Map.of(), Double.NaN, null));

	public DatabaseGauges(JdbcConnectionDetails connectionDetails, Clock clock, OrderSyncProperties properties) {
		Duration timeout = properties.metrics().dbQueryTimeout();
		HikariConfig config = new HikariConfig();
		config.setPoolName("metrics");
		config.setJdbcUrl(connectionDetails.getJdbcUrl());
		config.setUsername(connectionDetails.getUsername());
		config.setPassword(connectionDetails.getPassword());
		config.setMaximumPoolSize(1);
		config.setMinimumIdle(0);
		config.setConnectionTimeout(Math.max(250, timeout.toMillis()));
		config.setConnectionInitSql("SET statement_timeout = " + timeout.toMillis());
		// The app must start (and serve) even if the database is not reachable yet.
		config.setInitializationFailTimeout(-1);
		this.metricsDataSource = new HikariDataSource(config);
		this.jdbc = JdbcClient.create(this.metricsDataSource);
		this.clock = clock;
		this.refreshInterval = properties.metrics().dbRefreshInterval();
		this.startedAt = clock.instant();
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(this::refresh, this.refreshInterval);
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		for (String status : JOB_STATUSES) {
			Gauge.builder("ordersync.jobs", this, (gauges) -> gauges.count(gauges.snapshot.get().jobs(), status))
				.description("Sync jobs by status (from the last database snapshot)")
				.tag("status", status)
				.register(registry);
		}
		for (String status : OUTBOX_STATUSES) {
			Gauge.builder("ordersync.outbox.messages", this,
					(gauges) -> gauges.count(gauges.snapshot.get().outbox(), status))
				.description("Outbox messages by status (from the last database snapshot)")
				.tag("status", status)
				.register(registry);
		}
		Gauge.builder("ordersync.jobs.oldest.pending.age", this, (gauges) -> gauges.snapshot.get().oldestPendingAgeSeconds())
			.description("Age of the oldest job still waiting to be synced (PENDING or RETRY); 0 if none")
			.baseUnit("seconds")
			.register(registry);
		Gauge.builder("ordersync.db.snapshot.age", this, DatabaseGauges::snapshotAgeSeconds)
			.description("Seconds since the database gauges were last refreshed; grows while the database is unreachable")
			.baseUnit("seconds")
			.register(registry);
	}

	/** One round trip for every gauge. A failure keeps the previous snapshot. */
	void refresh() {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
		try {
			record Row(String kind, String status, double value) {
			}
			List<Row> rows = this.jdbc.sql("""
					SELECT 'job' AS kind, status, count(*)::float8 AS value FROM sync_job GROUP BY status
					UNION ALL
					SELECT 'outbox', status, count(*)::float8 FROM outbox_message GROUP BY status
					UNION ALL
					SELECT 'age', NULL, coalesce(extract(EPOCH FROM (:now - min(created_at))), 0)::float8
					FROM sync_job WHERE status IN ('PENDING', 'RETRY')
					""")
				.param("now", now)
				.query((rs, rowNum) -> new Row(rs.getString("kind"), rs.getString("status"), rs.getDouble("value")))
				.list();
			Map<String, Long> jobs = new HashMap<>();
			Map<String, Long> outbox = new HashMap<>();
			double age = 0;
			for (Row row : rows) {
				switch (row.kind()) {
					case "job" -> jobs.put(row.status(), (long) row.value());
					case "outbox" -> outbox.put(row.status(), (long) row.value());
					default -> age = row.value();
				}
			}
			this.snapshot.set(new Snapshot(Map.copyOf(jobs), Map.copyOf(outbox), age, now.toInstant()));
		}
		catch (DataAccessException ex) {
			log.warn("Could not refresh the database gauges; keeping the snapshot from {}: {}",
					this.snapshot.get().refreshedAt(), ex.getMostSpecificCause().getMessage());
		}
	}

	/** Before the first successful refresh, a status count is unknown (NaN), not zero. */
	private double count(Map<String, Long> counts, String status) {
		if (this.snapshot.get().refreshedAt() == null) {
			return Double.NaN;
		}
		return counts.getOrDefault(status, 0L);
	}

	private double snapshotAgeSeconds() {
		Instant refreshedAt = this.snapshot.get().refreshedAt();
		Instant since = (refreshedAt != null) ? refreshedAt : this.startedAt;
		return Duration.between(since, this.clock.instant()).toMillis() / 1000.0;
	}

	@Override
	public void destroy() {
		this.metricsDataSource.close();
	}

}
