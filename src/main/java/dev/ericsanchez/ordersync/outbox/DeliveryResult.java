package dev.ericsanchez.ordersync.outbox;

/** Outcome of one delivery attempt. The relay maps each case to a message state. */
public sealed interface DeliveryResult {

	/** The destination accepted the message (2xx). */
	record Delivered() implements DeliveryResult {
	}

	/** 408, 429, 5xx, timeout or IO error: worth trying again later. */
	record Retryable(String error) implements DeliveryResult {
	}

	/** Any other 4xx, or a message that cannot be sent at all: retrying will not help. */
	record Permanent(String error) implements DeliveryResult {
	}

	/** The circuit breaker refused the call; the destination was never contacted. */
	record CircuitOpen() implements DeliveryResult {
	}

	/** Not sent because newer information already exists; sending it would overwrite the newer value. */
	record Superseded(String reason) implements DeliveryResult {
	}

}
