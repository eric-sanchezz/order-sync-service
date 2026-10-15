package dev.ericsanchez.ordersync.outbox;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JdbcClient implementation of {@link OutboxQueries}, plugged into {@link OutboxRepository} as a Spring Data
 * fragment. Same conventions as the sync job queries: OffsetDateTime (UTC, microseconds) and a lease guard.
 */
class OutboxQueriesImpl implements OutboxQueries {

	private static final String NUL = String.valueOf((char) 0);

	private final JdbcClient jdbc;

	OutboxQueriesImpl(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public long enqueue(String aggregateType, String aggregateId, String eventType, OutboxDestination destination,
			String payload, OffsetDateTime now) {
		return this.jdbc.sql("""
				INSERT INTO outbox_message (aggregate_type, aggregate_id, event_type, destination, payload, status,
				                            attempts, next_attempt_at, created_at, updated_at)
				VALUES (:aggregateType, :aggregateId, :eventType, :destination, :payload, 'PENDING', 0, :now, :now, :now)
				RETURNING id
				""")
			.param("aggregateType", aggregateType)
			.param("aggregateId", aggregateId)
			.param("eventType", eventType)
			.param("destination", destination.name())
			.param("payload", payload)
			.param("now", now)
			.query(Long.class)
			.single();
	}

	@Override
	public int supersedePending(String aggregateId, OutboxDestination destination, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'SUPERSEDED', last_error = 'superseded by a newer message for the same key',
				    updated_at = :now
				WHERE aggregate_id = :aggregateId AND destination = :destination AND status IN ('PENDING', 'RETRY')
				""")
			.param("now", now)
			.param("aggregateId", aggregateId)
			.param("destination", destination.name())
			.update();
	}

	/**
	 * Claims due messages, in order per key (head-of-line): a message is only eligible if no older message with the
	 * same (aggregate_type, aggregate_id, destination) is still PENDING, RETRY or IN_PROGRESS. The type is part of
	 * the key: order 1001 and the SKU "1001" share KAFKA but are different aggregates. Only the oldest active message
	 * of a key can pass that test, so a batch never holds two messages of the same key, and a key whose head is waiting
	 * for a retry blocks only itself. DELIVERED and SUPERSEDED messages do not block. A DEAD message blocks its key
	 * only for KAFKA: an event published after a newer one of its key would leave consumers on a stale state, so the
	 * key waits until the DEAD event is replayed (ADR 0006). For the shop, the stock level is absolute and the
	 * staleness guard covers a late replay, so a DEAD message does not hold up newer ones.
	 * <p>
	 * Expired leases are claimed again and count an attempt (the previous relay probably called the destination and
	 * lost the result), as for sync jobs. SKIP LOCKED lets several relays claim concurrently.
	 */
	@Override
	public List<ClaimedMessage> claim(OffsetDateTime now, OffsetDateTime lockedUntil, int batchSize) {
		return this.jdbc.sql("""
				UPDATE outbox_message m
				SET status = 'IN_PROGRESS', locked_until = :lockedUntil, updated_at = :now,
				    attempts = m.attempts + CASE WHEN m.status = 'IN_PROGRESS' THEN 1 ELSE 0 END
				WHERE m.id IN (SELECT c.id FROM outbox_message c
				               WHERE ((c.status IN ('PENDING', 'RETRY') AND c.next_attempt_at <= :now)
				                      OR (c.status = 'IN_PROGRESS' AND c.locked_until < :now))
				                 AND NOT EXISTS (SELECT 1 FROM outbox_message older
				                                 WHERE older.aggregate_type = c.aggregate_type
				                                   AND older.aggregate_id = c.aggregate_id
				                                   AND older.destination = c.destination
				                                   AND older.id < c.id
				                                   AND (older.status IN ('PENDING', 'RETRY', 'IN_PROGRESS')
				                                        OR (older.destination = 'KAFKA' AND older.status = 'DEAD')))
				               ORDER BY c.next_attempt_at, c.id
				               LIMIT :batchSize
				               FOR UPDATE SKIP LOCKED)
				RETURNING m.id, m.destination, m.aggregate_id, m.event_type, m.payload, m.attempts, m.locked_until
				""")
			.param("now", now)
			.param("lockedUntil", lockedUntil)
			.param("batchSize", batchSize)
			.query((rs, rowNum) -> new ClaimedMessage(rs.getLong("id"),
					OutboxDestination.valueOf(rs.getString("destination")), rs.getString("aggregate_id"),
					rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts"),
					rs.getObject("locked_until", OffsetDateTime.class)))
			.list();
	}

	@Override
	public boolean markDelivered(ClaimedMessage message, int attempts, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'DELIVERED', attempts = :attempts, last_error = NULL, locked_until = NULL,
				    updated_at = :now, delivered_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("now", now)
			.param("id", message.id())
			.param("lockedUntil", message.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean markRetry(ClaimedMessage message, int attempts, OffsetDateTime nextAttemptAt, String error,
			OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'RETRY', attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :error,
				    locked_until = NULL, updated_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("nextAttemptAt", nextAttemptAt)
			.param("error", storable(error))
			.param("now", now)
			.param("id", message.id())
			.param("lockedUntil", message.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean markDead(ClaimedMessage message, int attempts, String error, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'DEAD', attempts = :attempts, last_error = :error, locked_until = NULL, updated_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("error", storable(error))
			.param("now", now)
			.param("id", message.id())
			.param("lockedUntil", message.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean markSuperseded(ClaimedMessage message, String reason, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'SUPERSEDED', last_error = :reason, locked_until = NULL, updated_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("reason", storable(reason))
			.param("now", now)
			.param("id", message.id())
			.param("lockedUntil", message.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean hasNewerMessage(ClaimedMessage message) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM outbox_message
				               WHERE aggregate_id = :aggregateId AND destination = :destination AND id > :id)
				""")
			.param("aggregateId", message.aggregateId())
			.param("destination", message.destination().name())
			.param("id", message.id())
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean replay(long messageId, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_message
				SET status = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL,
				    locked_until = NULL, delivered_at = NULL, updated_at = :now
				WHERE id = :id AND status = 'DEAD'
				""")
			.param("now", now)
			.param("id", messageId)
			.update() == 1;
	}

	/** Postgres text cannot hold U+0000 (an error body may contain it); written as <NUL> instead. */
	private static String storable(String text) {
		return (text != null) ? text.replace(NUL, "<NUL>") : null;
	}

}
