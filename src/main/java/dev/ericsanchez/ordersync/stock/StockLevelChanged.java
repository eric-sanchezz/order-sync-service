package dev.ericsanchez.ordersync.stock;

import java.time.Instant;

/** A validated stock change. It is also the payload of the outbox message sent to the shop. */
public record StockLevelChanged(String articleCode, long quantityAvailable, String warehouse, Instant changedAt) {

	public static final String EVENT_TYPE = "StockLevelChanged";

	public static final String AGGREGATE_TYPE = "Stock";

}
