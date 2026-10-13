package dev.ericsanchez.ordersync.erp;

/** Outcome of one attempt to create a sales order in the ERP. The worker maps each case to a job state. */
public sealed interface ErpResult {

	/** 2xx. {@code orderId} may be null if the ERP did not return one. */
	record Success(String orderId) implements ErpResult {
	}

	/** 408, 429, 5xx, timeout or IO error: worth trying again later. */
	record Retryable(String error) implements ErpResult {
	}

	/** Any other 4xx: the ERP rejected this order and retrying will not help. */
	record Permanent(String error) implements ErpResult {
	}

	/** The circuit breaker refused the call; the ERP was never contacted. */
	record CircuitOpen() implements ErpResult {
	}

}
