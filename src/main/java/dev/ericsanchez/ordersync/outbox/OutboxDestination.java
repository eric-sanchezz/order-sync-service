package dev.ericsanchez.ordersync.outbox;

/** Where an outbox message goes. Each destination has exactly one {@link OutboxDispatcher}. */
public enum OutboxDestination {

	/** The shop's stock API (PUT stock by SKU). */
	SHOP_STOCK_API

}
