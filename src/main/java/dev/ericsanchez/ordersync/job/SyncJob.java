package dev.ericsanchez.ordersync.job;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read model of a sync job, used by the admin API. All state changes go through the native SQL in
 * {@link SyncJobQueries}, so this entity has no setters.
 */
@Entity
@Table(name = "sync_job")
public class SyncJob {

	@Id
	private Long id;

	@Column(name = "inbox_event_id", nullable = false, unique = true)
	private long inboxEventId;

	@Column(name = "event_id", nullable = false, length = 200)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private JobStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	@Column(name = "erp_order_id", columnDefinition = "text")
	private String erpOrderId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected SyncJob() {
	}

	public Long getId() {
		return this.id;
	}

	public long getInboxEventId() {
		return this.inboxEventId;
	}

	public String getEventId() {
		return this.eventId;
	}

	public JobStatus getStatus() {
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

	public String getErpOrderId() {
		return this.erpOrderId;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

}
