package dev.ericsanchez.ordersync.job;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Native SQL on {@code sync_job}: the claim, the guarded result updates, replay and stats. Each method is one
 * statement in its own short transaction; the ERP call always happens between them, never inside one.
 * The {@code mark*} methods return false when the guard matched no row (the lease was lost).
 */
public interface SyncJobQueries {

	List<ClaimedJob> claim(OffsetDateTime now, OffsetDateTime lockedUntil, int batchSize);

	boolean markDone(ClaimedJob job, int attempts, String erpOrderId, OffsetDateTime now);

	boolean markRetry(ClaimedJob job, int attempts, OffsetDateTime nextAttemptAt, String error, OffsetDateTime now);

	boolean markDead(ClaimedJob job, int attempts, String error, OffsetDateTime now);

	/** DEAD -> PENDING with a fresh attempt budget. False if the job is missing or not DEAD. */
	boolean replay(long jobId, OffsetDateTime now);

	Optional<String> findPayload(long jobId);

	Map<JobStatus, Long> countByStatus();

	/** End-to-end latency (created_at -> completed_at) of DONE jobs; all null when there are none. */
	EndToEndLatency endToEndLatency();

	record EndToEndLatency(Long p50, Long p95, Long max) {
	}

}
