package dev.ericsanchez.ordersync.job;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JdbcClient implementation of {@link SyncJobQueries}, plugged into {@link SyncJobRepository} as a Spring Data
 * fragment. Timestamps are bound as {@link OffsetDateTime} (UTC, microseconds) because that is what timestamptz holds.
 */
class SyncJobQueriesImpl implements SyncJobQueries {

	private static final String NUL = String.valueOf((char) 0);

	private final JdbcClient jdbc;

	SyncJobQueriesImpl(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Claims due jobs: PENDING/RETRY whose time has come, plus IN_PROGRESS jobs whose lease expired (the worker that
	 * held them died). SKIP LOCKED lets several workers claim concurrently without ever getting the same row.
	 * Re-claiming an expired lease counts one attempt: that worker probably called the ERP and its result was lost,
	 * so a job that keeps crashing its worker still runs out of attempts instead of looping forever.
	 */
	@Override
	public List<ClaimedJob> claim(OffsetDateTime now, OffsetDateTime lockedUntil, int batchSize) {
		return this.jdbc.sql("""
				UPDATE sync_job j
				SET status = 'IN_PROGRESS', locked_until = :lockedUntil, updated_at = :now,
				    attempts = j.attempts + CASE WHEN j.status = 'IN_PROGRESS' THEN 1 ELSE 0 END
				FROM inbox_event e
				WHERE e.id = j.inbox_event_id
				  AND j.id IN (SELECT id FROM sync_job
				               WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
				                  OR (status = 'IN_PROGRESS' AND locked_until < :now)
				               ORDER BY next_attempt_at
				               LIMIT :batchSize
				               FOR UPDATE SKIP LOCKED)
				RETURNING j.id, j.event_id, j.shop_order_id, j.attempts, j.locked_until, e.payload
				""")
			.param("now", now)
			.param("lockedUntil", lockedUntil)
			.param("batchSize", batchSize)
			.query((rs, rowNum) -> new ClaimedJob(rs.getLong("id"), rs.getString("event_id"),
					rs.getObject("shop_order_id", Long.class), rs.getInt("attempts"),
					rs.getObject("locked_until", OffsetDateTime.class), rs.getString("payload")))
			.list();
	}

	@Override
	public boolean markDone(ClaimedJob job, int attempts, String erpOrderId, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE sync_job
				SET status = 'DONE', attempts = :attempts, erp_order_id = :erpOrderId, last_error = NULL,
				    locked_until = NULL, updated_at = :now, completed_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("erpOrderId", storable(erpOrderId))
			.param("now", now)
			.param("id", job.id())
			.param("lockedUntil", job.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean markRetry(ClaimedJob job, int attempts, OffsetDateTime nextAttemptAt, String error,
			OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE sync_job
				SET status = 'RETRY', attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :error,
				    locked_until = NULL, updated_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("nextAttemptAt", nextAttemptAt)
			.param("error", storable(error))
			.param("now", now)
			.param("id", job.id())
			.param("lockedUntil", job.lockedUntil())
			.update() == 1;
	}

	@Override
	public boolean markDead(ClaimedJob job, int attempts, String error, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE sync_job
				SET status = 'DEAD', attempts = :attempts, last_error = :error,
				    locked_until = NULL, updated_at = :now, completed_at = :now
				WHERE id = :id AND status = 'IN_PROGRESS' AND locked_until = :lockedUntil
				""")
			.param("attempts", attempts)
			.param("error", storable(error))
			.param("now", now)
			.param("id", job.id())
			.param("lockedUntil", job.lockedUntil())
			.update() == 1;
	}

	/** Postgres text cannot hold U+0000 (an ERP error body may contain it); written as <NUL> instead. */
	private static String storable(String text) {
		return (text != null) ? text.replace(NUL, "<NUL>") : null;
	}

	@Override
	public boolean replay(long jobId, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE sync_job
				SET status = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = NULL,
				    locked_until = NULL, erp_order_id = NULL, completed_at = NULL, updated_at = :now
				WHERE id = :id AND status = 'DEAD'
				""")
			.param("now", now)
			.param("id", jobId)
			.update() == 1;
	}

	@Override
	public Optional<String> findPayload(long jobId) {
		return this.jdbc.sql("""
				SELECT e.payload FROM sync_job j JOIN inbox_event e ON e.id = j.inbox_event_id WHERE j.id = :id
				""")
			.param("id", jobId)
			.query(String.class)
			.optional();
	}

	@Override
	public Map<JobStatus, Long> countByStatus() {
		Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
		for (JobStatus status : JobStatus.values()) {
			counts.put(status, 0L);
		}
		this.jdbc.sql("SELECT status, count(*) AS total FROM sync_job GROUP BY status")
			.query((rs, rowNum) -> Map.entry(JobStatus.valueOf(rs.getString("status")), rs.getLong("total")))
			.list()
			.forEach((entry) -> counts.put(entry.getKey(), entry.getValue()));
		return counts;
	}

	@Override
	public EndToEndLatency endToEndLatency() {
		return this.jdbc.sql("""
				SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms))::bigint AS p50,
				       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms))::bigint AS p95,
				       round(max(ms))::bigint AS max
				FROM (SELECT extract(EPOCH FROM (completed_at - created_at)) * 1000 AS ms
				      FROM sync_job WHERE status = 'DONE') done
				""")
			.query((rs, rowNum) -> new EndToEndLatency(rs.getObject("p50", Long.class),
					rs.getObject("p95", Long.class), rs.getObject("max", Long.class)))
			.single();
	}

}
