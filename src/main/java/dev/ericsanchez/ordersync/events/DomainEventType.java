package dev.ericsanchez.ordersync.events;

import java.util.Arrays;
import java.util.Optional;

/** The domain events this service publishes. Orders and stock go to different topics (see {@link KafkaDispatcher}). */
public enum DomainEventType {

	/** A sync job reached DONE: the ERP accepted the order. */
	ORDER_SYNCED("OrderSynced", Aggregate.ORDER),

	/** A sync job reached DEAD: the order will not reach the ERP unless someone replays it. */
	ORDER_FAILED("OrderFailed", Aggregate.ORDER),

	/** A stock change from the ERP was applied (not stale, not a duplicate). */
	STOCK_UPDATED("StockUpdated", Aggregate.STOCK);

	/** What the event is about; it decides the topic and the partition key. */
	public enum Aggregate {

		/** Key: the shop order id. */
		ORDER,

		/** Key: the article code (SKU). */
		STOCK

	}

	private final String eventName;

	private final Aggregate aggregate;

	DomainEventType(String eventName, Aggregate aggregate) {
		this.eventName = eventName;
		this.aggregate = aggregate;
	}

	/** The {@code type} of the envelope and the {@code eventType} header, e.g. {@code OrderSynced}. */
	public String eventName() {
		return this.eventName;
	}

	public Aggregate aggregate() {
		return this.aggregate;
	}

	public static Optional<DomainEventType> fromEventName(String eventName) {
		return Arrays.stream(values()).filter((type) -> type.eventName.equals(eventName)).findFirst();
	}

}
