package dev.ericsanchez.ordersync.webhook;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Stores a verified shop event and its sync job in one transaction. This is the first idempotency layer, with two
 * keys: the UNIQUE event_id (a redelivered webhook) and the UNIQUE shop order id on the job. A shop order never gets
 * a second job. A new delivery of a known order (new event id) is handled by the state of its job (ADR 0002):
 * <ul>
 * <li>PENDING or RETRY: the job takes the new payload (the last delivery wins) and keeps its attempts;</li>
 * <li>DEAD: the job takes the new payload and starts over (PENDING, 0 attempts): the shop fixed the order;</li>
 * <li>IN_PROGRESS: refused, nothing stored, so the shop retries the same webhook later;</li>
 * <li>DONE: a duplicate; changing an order that already exists in the ERP is out of scope.</li>
 * </ul>
 * The job row is locked while deciding, so the worker cannot claim it in between (its claim skips locked rows).
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
	}

	public record Accepted(long jobId) implements IngestResult {
	}

	/** {@code jobId} is null only if a reused event id points to a different order than the first time. */
	public record Duplicate(Long jobId) implements IngestResult {
	}

	/** The order's job is being sent to the ERP right now; nothing was stored. */
	public record InProgress(long jobId) implements IngestResult {
	}

	@Transactional
	public IngestResult ingest(String eventId, String payload, Long shopOrderId) {
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
			return new Duplicate(existingJob(eventId, shopOrderId).orElse(null));
		}
		// An order without id gets no business key (NULLs never conflict); the worker sends it DEAD.
		Optional<Long> jobId = this.jdbc.sql("""
				INSERT INTO sync_job (inbox_event_id, event_id, shop_order_id, status, attempts, next_attempt_at,
				                      created_at, updated_at)
				VALUES (:inboxEventId, :eventId, :shopOrderId, 'PENDING', 0, :now, :now, :now)
				ON CONFLICT (shop_order_id) DO NOTHING
				RETURNING id
				""")
			.param("inboxEventId", inboxEventId.get())
			.param("eventId", eventId)
			.param("shopOrderId", shopOrderId)
			.param("now", now)
			.query(Long.class)
			.optional();
		if (jobId.isPresent()) {
			return new Accepted(jobId.get());
		}
		return redeliver(shopOrderId, inboxEventId.get(), eventId, now);
	}

	/** A new delivery of an order that already has a job. Runs inside {@link #ingest}'s transaction. */
	private IngestResult redeliver(long shopOrderId, long inboxEventId, String eventId, OffsetDateTime now) {
		record ExistingJob(long id, String status) {
		}
		ExistingJob job = this.jdbc.sql("SELECT id, status FROM sync_job WHERE shop_order_id = :shopOrderId FOR UPDATE")
			.param("shopOrderId", shopOrderId)
			.query((rs, rowNum) -> new ExistingJob(rs.getLong("id"), rs.getString("status")))
			.single();
		switch (job.status()) {
			case "PENDING", "RETRY" -> this.jdbc.sql("""
					UPDATE sync_job SET inbox_event_id = :inboxEventId, event_id = :eventId, updated_at = :now
					WHERE id = :id
					""")
				.param("inboxEventId", inboxEventId)
				.param("eventId", eventId)
				.param("now", now)
				.param("id", job.id())
				.update();
			case "DEAD" -> this.jdbc.sql("""
					UPDATE sync_job
					SET inbox_event_id = :inboxEventId, event_id = :eventId, status = 'PENDING', attempts = 0,
					    next_attempt_at = :now, locked_until = NULL, last_error = NULL, erp_order_id = NULL,
					    completed_at = NULL, updated_at = :now
					WHERE id = :id
					""")
				.param("inboxEventId", inboxEventId)
				.param("eventId", eventId)
				.param("now", now)
				.param("id", job.id())
				.update();
			case "IN_PROGRESS" -> {
				// Keeping this inbox row would turn the shop's retry of the same event into a "duplicate".
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return new InProgress(job.id());
			}
			default -> {
				return new Duplicate(job.id());
			}
		}
		return new Accepted(job.id());
	}

	private Optional<Long> existingJob(String eventId, Long shopOrderId) {
		return this.jdbc.sql("""
				SELECT id FROM sync_job WHERE shop_order_id = :shopOrderId OR event_id = :eventId ORDER BY id LIMIT 1
				""")
			.param("shopOrderId", shopOrderId)
			.param("eventId", eventId)
			.query(Long.class)
			.optional();
	}

}
