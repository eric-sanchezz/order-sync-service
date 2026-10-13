package dev.ericsanchez.ordersync.webhook;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a verified shop event and its sync job in one transaction. The UNIQUE event_id plus
 * {@code ON CONFLICT DO NOTHING} is the first idempotency layer: a redelivered webhook never creates a second job.
 * <p>
 * It writes the {@code sync_job} row with its own SQL so that {@code webhook} does not depend on {@code job}
 * (the worker already depends on {@link ShopOrder}).
 */
@Service
public class InboxService {

	private final JdbcClient jdbc;

	private final Clock clock;

	public InboxService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public sealed interface IngestResult {

		long jobId();

	}

	public record Accepted(long jobId) implements IngestResult {
	}

	public record Duplicate(long jobId) implements IngestResult {
	}

	@Transactional
	public IngestResult ingest(String eventId, String payload) {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC)
			.truncatedTo(ChronoUnit.MICROS);
		Optional<Long> inboxEventId = this.jdbc.sql("""
				INSERT INTO inbox_event (event_id, payload, received_at)
				VALUES (:eventId, :payload, :now)
				ON CONFLICT (event_id) DO NOTHING
				RETURNING id
				""")
			.param("eventId", eventId)
			.param("payload", payload)
			.param("now", now)
			.query(Long.class)
			.optional();
		if (inboxEventId.isEmpty()) {
			long existingJobId = this.jdbc.sql("SELECT id FROM sync_job WHERE event_id = :eventId")
				.param("eventId", eventId)
				.query(Long.class)
				.single();
			return new Duplicate(existingJobId);
		}
		long jobId = this.jdbc.sql("""
				INSERT INTO sync_job (inbox_event_id, event_id, status, attempts, next_attempt_at, created_at, updated_at)
				VALUES (:inboxEventId, :eventId, 'PENDING', 0, :now, :now, :now)
				RETURNING id
				""")
			.param("inboxEventId", inboxEventId.get())
			.param("eventId", eventId)
			.param("now", now)
			.query(Long.class)
			.single();
		return new Accepted(jobId);
	}

}
