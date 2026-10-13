package dev.ericsanchez.ordersync.admin;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxMessage;
import dev.ericsanchez.ordersync.outbox.OutboxStatus;

/** Everything needed to debug a message, including its payload (embedded as JSON, not as a string). */
public record OutboxDetail(
		long id,
		String aggregateType,
		String aggregateId,
		String eventType,
		OutboxDestination destination,
		OutboxStatus status,
		int attempts,
		String lastError,
		Instant nextAttemptAt,
		Instant lockedUntil,
		Instant createdAt,
		Instant updatedAt,
		Instant deliveredAt,
		@JsonRawValue String payload) {

	static OutboxDetail from(OutboxMessage message) {
		return new OutboxDetail(message.getId(), message.getAggregateType(), message.getAggregateId(),
				message.getEventType(), message.getDestination(), message.getStatus(), message.getAttempts(),
				message.getLastError(), message.getNextAttemptAt(), message.getLockedUntil(), message.getCreatedAt(),
				message.getUpdatedAt(), message.getDeliveredAt(), message.getPayload());
	}

}
