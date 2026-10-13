package dev.ericsanchez.ordersync.outbox;

/**
 * Sends outbox messages of one destination. Implementations never throw for HTTP or IO problems: they return a
 * {@link DeliveryResult}. Anything they do throw is treated as a retryable failure by the relay.
 */
public interface OutboxDispatcher {

	OutboxDestination destination();

	DeliveryResult deliver(ClaimedMessage message);

}
