package dev.ericsanchez.ordersync.outbox;

/**
 * Lifecycle of an outbox message. DELIVERED, DEAD and SUPERSEDED are final (a DEAD message can be replayed).
 * SUPERSEDED means a newer message for the same key made this one pointless, so it was never sent.
 */
public enum OutboxStatus {

	PENDING, IN_PROGRESS, RETRY, DELIVERED, DEAD, SUPERSEDED

}
