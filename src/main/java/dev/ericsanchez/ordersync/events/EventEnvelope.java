package dev.ericsanchez.ordersync.events;

/**
 * The JSON of every event, version 1. Stable: fields are only ever added; a breaking change gets a new version and a
 * new topic ({@code .v2}).
 *
 * @param eventId unique per event (UUID); consumers deduplicate by it (delivery is at least once)
 * @param type {@code OrderSynced}, {@code OrderFailed} or {@code StockUpdated}
 * @param aggregateId the shop order id or the article code; also the Kafka record key
 * @param sequence 1, 2, 3... per aggregate, in the order the changes were committed; a consumer that has already
 *        seen a higher sequence for this aggregate can drop the event as stale (a replay keeps its sequence)
 * @param occurredAt when the state change was committed (ISO-8601, UTC)
 * @param version of this envelope and of the payload of {@code type}
 * @param correlationId the webhook event id that started it all (shop order event or ERP stock event)
 * @param payload the event's own fields
 */
public record EventEnvelope(String eventId, String type, String aggregateId, long sequence, String occurredAt,
		int version, String correlationId, Object payload) {

	public static final int VERSION = 1;

	/** Payload of {@code OrderSynced}. {@code shopOrderId} is null only for an order that arrived without an id. */
	public record OrderSynced(Long shopOrderId, long jobId, String erpOrderId, long totalAmountCents,
			String currency) {
	}

	/** Payload of {@code OrderFailed}: {@code reason} is the job's last error. */
	public record OrderFailed(Long shopOrderId, long jobId, String reason, int attempts) {
	}

	/** Payload of {@code StockUpdated}: the stock level as applied. */
	public record StockUpdated(String articleCode, long quantityAvailable, String warehouse, String changedAt) {
	}

}
