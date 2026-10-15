package dev.ericsanchez.ordersync.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxRepository;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records domain events in the outbox (destination KAFKA); {@link KafkaDispatcher} publishes them later. Every method
 * must run inside the transaction of the state change it reports, so that the event exists if, and only if, the
 * change was committed: never a "DONE" without its event, never an event for a change that was rolled back
 * (ADR 0006). With {@code ordersync.events.kafka.enabled=false} nothing is written.
 */
@Component
public class DomainEvents {

	private final OutboxRepository outbox;

	private final JsonMapper jsonMapper;

	private final EventSequences sequences;

	private final boolean enabled;

	DomainEvents(OutboxRepository outbox, EventSequences sequences, JsonMapper jsonMapper,
			OrderSyncProperties properties) {
		this.outbox = outbox;
		this.sequences = sequences;
		this.jsonMapper = jsonMapper;
		this.enabled = properties.events().kafka().enabled();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void orderSynced(long jobId, Long shopOrderId, String correlationId, String erpOrderId,
			long totalAmountCents, String currency, OffsetDateTime now) {
		write(DomainEventType.ORDER_SYNCED, orderKey(jobId, shopOrderId), correlationId,
				new EventEnvelope.OrderSynced(shopOrderId, jobId, erpOrderId, totalAmountCents, currency), now);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void orderFailed(long jobId, Long shopOrderId, String correlationId, String reason, int attempts,
			OffsetDateTime now) {
		write(DomainEventType.ORDER_FAILED, orderKey(jobId, shopOrderId), correlationId,
				new EventEnvelope.OrderFailed(shopOrderId, jobId, reason, attempts), now);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void stockUpdated(String articleCode, long quantityAvailable, String warehouse, Instant changedAt,
			String correlationId, OffsetDateTime now) {
		write(DomainEventType.STOCK_UPDATED, articleCode, correlationId,
				new EventEnvelope.StockUpdated(articleCode, quantityAvailable, warehouse, changedAt.toString()), now);
	}

	/**
	 * The partition key of an order event. An order that arrived without an id (it goes DEAD at once) has no shop
	 * order id; its events use {@code job-<job id>} instead.
	 */
	private static String orderKey(long jobId, Long shopOrderId) {
		return (shopOrderId != null) ? String.valueOf(shopOrderId) : "job-" + jobId;
	}

	private void write(DomainEventType type, String aggregateId, String correlationId, Object payload,
			OffsetDateTime now) {
		if (!this.enabled) {
			return;
		}
		// The sequence first: its row lock orders this transaction behind any other writer of the aggregate, so the
		// outbox id taken next follows the same order as the sequence.
		long sequence = this.sequences.next(type.aggregate().name(), aggregateId);
		EventEnvelope envelope = new EventEnvelope(UUID.randomUUID().toString(), type.eventName(), aggregateId,
				sequence, now.toInstant().toString(), EventEnvelope.VERSION, correlationId, payload);
		this.outbox.enqueue(type.aggregate().name(), aggregateId, type.eventName(), OutboxDestination.KAFKA,
				this.jsonMapper.writeValueAsString(envelope), now);
	}

}
