package dev.ericsanchez.ordersync.outbox;

import java.time.OffsetDateTime;

/**
 * An outbox message this relay holds a lease on. {@code lockedUntil} is the value read back from the database and
 * guards the result update, exactly like {@code ClaimedJob} does for sync jobs.
 */
public record ClaimedMessage(long id, OutboxDestination destination, String aggregateId, String eventType,
		String payload, int attempts, OffsetDateTime lockedUntil) {
}
