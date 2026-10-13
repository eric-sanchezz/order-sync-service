package dev.ericsanchez.ordersync.admin;

import java.time.Instant;

import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxMessage;
import dev.ericsanchez.ordersync.outbox.OutboxStatus;

public record OutboxSummary(
		long id,
		OutboxDestination destination,
		String aggregateId,
		String eventType,
		OutboxStatus status,
		int attempts,
		String lastError,
		Instant nextAttemptAt,
		Instant createdAt,
		Instant deliveredAt) {

	static OutboxSummary from(OutboxMessage message) {
		return new OutboxSummary(message.getId(), message.getDestination(), message.getAggregateId(),
				message.getEventType(), message.getStatus(), message.getAttempts(), message.getLastError(),
				message.getNextAttemptAt(), message.getCreatedAt(), message.getDeliveredAt());
	}

}
