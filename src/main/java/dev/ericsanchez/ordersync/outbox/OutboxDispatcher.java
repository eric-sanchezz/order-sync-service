package dev.ericsanchez.ordersync.outbox;

/**
 * Sends outbox messages of one destination. Implementations never throw for HTTP or IO problems: they return a
 * {@link DeliveryResult}. Anything they do throw is treated as a retryable failure by the relay.
 */
public interface OutboxDispatcher {

	OutboxDestination destination();

	DeliveryResult deliver(ClaimedMessage message);

	/**
	 * False (the default): a message that keeps failing with retryable errors goes DEAD after
	 * {@code ordersync.retry.max-attempts}. True: retryable failures (and expired leases) never send a message DEAD;
	 * it is retried with backoff until the destination takes it. Only {@link DeliveryResult.Permanent} ends it.
	 */
	default boolean retriesUntilDelivered() {
		return false;
	}

}
