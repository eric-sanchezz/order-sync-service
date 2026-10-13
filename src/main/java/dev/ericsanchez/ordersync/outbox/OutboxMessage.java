package dev.ericsanchez.ordersync.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read model of an outbox message, used by the admin API. All state changes go through the native SQL in
 * {@link OutboxQueries}, so this entity has no setters.
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

	@Id
	private Long id;

	@Column(name = "aggregate_type", nullable = false, length = 30)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, length = 100)
	private String aggregateId;

	@Column(name = "event_type", nullable = false, length = 50)
	private String eventType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private OutboxDestination destination;

	@Column(nullable = false, columnDefinition = "text")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	protected OutboxMessage() {
	}

	public Long getId() {
		return this.id;
	}

	public String getAggregateType() {
		return this.aggregateType;
	}

	public String getAggregateId() {
		return this.aggregateId;
	}

	public String getEventType() {
		return this.eventType;
	}

	public OutboxDestination getDestination() {
		return this.destination;
	}

	public String getPayload() {
		return this.payload;
	}

	public OutboxStatus getStatus() {
		return this.status;
	}

	public int getAttempts() {
		return this.attempts;
	}

	public Instant getNextAttemptAt() {
		return this.nextAttemptAt;
	}

	public Instant getLockedUntil() {
		return this.lockedUntil;
	}

	public String getLastError() {
		return this.lastError;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getDeliveredAt() {
		return this.deliveredAt;
	}

}
