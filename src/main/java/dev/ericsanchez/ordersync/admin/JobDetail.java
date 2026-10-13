package dev.ericsanchez.ordersync.admin;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.ericsanchez.ordersync.job.JobStatus;
import dev.ericsanchez.ordersync.job.SyncJob;

/** Everything needed to debug a job, including the original shop payload (embedded as JSON, not as a string). */
public record JobDetail(
		long id,
		String eventId,
		JobStatus status,
		int attempts,
		String lastError,
		String erpOrderId,
		Instant nextAttemptAt,
		Instant lockedUntil,
		Instant createdAt,
		Instant updatedAt,
		Instant completedAt,
		@JsonRawValue String payload) {

	static JobDetail from(SyncJob job, String payload) {
		return new JobDetail(job.getId(), job.getEventId(), job.getStatus(), job.getAttempts(), job.getLastError(),
				job.getErpOrderId(), job.getNextAttemptAt(), job.getLockedUntil(), job.getCreatedAt(),
				job.getUpdatedAt(), job.getCompletedAt(), payload);
	}

}
