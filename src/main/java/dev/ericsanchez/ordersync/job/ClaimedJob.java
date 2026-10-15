package dev.ericsanchez.ordersync.job;

import java.time.OffsetDateTime;

/**
 * A job this worker holds a lease on. {@code lockedUntil} is the exact value read back from the database: the result
 * update uses it as a guard, so a worker whose lease expired (and was re-claimed by another) cannot overwrite it.
 * {@code shopOrderId} is null for an order that arrived without an id (it goes DEAD).
 */
public record ClaimedJob(long id, String eventId, Long shopOrderId, int attempts, OffsetDateTime lockedUntil,
		String payload) {
}
