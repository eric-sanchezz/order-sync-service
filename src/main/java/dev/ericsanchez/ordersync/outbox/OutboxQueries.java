package dev.ericsanchez.ordersync.outbox;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Native SQL on {@code outbox_message}. {@link #enqueue} and {@link #supersedePending} join the caller's transaction,
 * which is the point of an outbox: the message is stored if, and only if, the state change is. The other methods
 * are single statements; the delivery itself always happens between them, outside any transaction.
 * The {@code mark*} methods return false when the lease guard matched no row.
 */
public interface OutboxQueries {

	long enqueue(String aggregateType, String aggregateId, String eventType, OutboxDestination destination,
			String payload, OffsetDateTime now);

	/** Marks the not-yet-sent messages of a key as SUPERSEDED (in-flight ones are left alone). */
	int supersedePending(String aggregateId, OutboxDestination destination, OffsetDateTime now);

	List<ClaimedMessage> claim(OffsetDateTime now, OffsetDateTime lockedUntil, int batchSize);

	boolean markDelivered(ClaimedMessage message, int attempts, OffsetDateTime now);

	boolean markRetry(ClaimedMessage message, int attempts, OffsetDateTime nextAttemptAt, String error,
			OffsetDateTime now);

	boolean markDead(ClaimedMessage message, int attempts, String error, OffsetDateTime now);

	boolean markSuperseded(ClaimedMessage message, String reason, OffsetDateTime now);

	/**
	 * True if a newer message (higher id) exists for the same key and destination, in any status. For destinations
	 * that carry state (the latest value wins) such a message makes this one obsolete.
	 */
	boolean hasNewerMessage(ClaimedMessage message);

	/** DEAD -> PENDING with a fresh attempt budget. False if the message is missing or not DEAD. */
	boolean replay(long messageId, OffsetDateTime now);

}
