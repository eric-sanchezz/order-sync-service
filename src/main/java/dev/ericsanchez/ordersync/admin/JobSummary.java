package dev.ericsanchez.ordersync.admin;

import java.time.Instant;

import dev.ericsanchez.ordersync.job.JobStatus;
import dev.ericsanchez.ordersync.job.SyncJob;

public record JobSummary(
		long id,
		String eventId,
		JobStatus status,
		int attempts,
		String lastError,
		String erpOrderId,
		Instant nextAttemptAt,
		Instant createdAt,
		Instant completedAt) {

	static JobSummary from(SyncJob job) {
		return new JobSummary(job.getId(), job.getEventId(), job.getStatus(), job.getAttempts(), job.getLastError(),
				job.getErpOrderId(), job.getNextAttemptAt(), job.getCreatedAt(), job.getCompletedAt());
	}

}
