# 0001. Postgres as the job queue, with FOR UPDATE SKIP LOCKED

- Status: accepted
- Date: 2026-10-13

## Context

Every accepted shop order becomes a job that has to be sent to the ERP, possibly several times, and whose state an
operator must be able to see and change (replay). We need several workers (threads or instances) to share the jobs
without two of them sending the same order at the same time.

The options were a broker (Kafka, RabbitMQ) or the database we already have.

## Decision

The queue is the `sync_job` table in Postgres. A worker claims a batch with one statement:

```sql
UPDATE sync_job SET status = 'IN_PROGRESS', locked_until = :lease, ...
WHERE id IN (SELECT id FROM sync_job
             WHERE (status IN ('PENDING','RETRY') AND next_attempt_at <= :now)
                OR (status = 'IN_PROGRESS' AND locked_until < :now)
             ORDER BY next_attempt_at LIMIT :batch
             FOR UPDATE SKIP LOCKED)
RETURNING ...
```

- `SKIP LOCKED` makes concurrent claimers skip rows another claimer is taking instead of waiting for them or taking
  them twice.
- The **lease** (`locked_until`, 60 s) covers crashes: if a worker dies mid-job, the lease expires and the job can
  be claimed again.
- The ERP call happens **outside** any transaction, so no connection or row lock is held while waiting on the
  network. The result is written with a guard (`WHERE id = ? AND status = 'IN_PROGRESS' AND locked_until = ?`). If
  the lease was lost and the job was re-claimed, the late result changes 0 rows and is only logged.
- Re-claiming an expired lease counts one attempt: that worker most likely called the ERP and its result was lost.
  A job whose worker keeps dying therefore runs out of attempts and ends `DEAD` instead of looping forever. What
  happens when the result write itself fails is in ADR 0002.
- `(status, next_attempt_at)` is indexed for the claim.

## Consequences

- No extra infrastructure. The webhook stores the event and creates the job in the same local transaction, so there
  is no "saved but not enqueued" gap.
- Job state can be queried and fixed with plain SQL, and the admin API (list, replay, stats) is just queries.
- Retry scheduling is a column (`next_attempt_at`), not broker redelivery.
- Throughput is limited by Postgres. That is fine for B2B orders (tens per second), not for a firehose.
- A slow worker can lose its lease and the job gets sent twice. Only a worker that has not finished after 60 s can
  hit this; the ERP's `Idempotency-Key` (ADR 0002) covers it.
- The test suite checks this with two workers polling at the same moment while another transaction holds the oldest
  rows locked. Without `SKIP LOCKED`, both workers wait for those rows and then both send them.

## Update (2026-10-14): Kafka is now in the stack, and the queue stays here

Kafka was added to publish domain events (ADR 0006). The work queue was not moved to it, because what a job needs
is still what a table gives and a log does not:
- **Per-message retries with their own schedule.** A job waits in `RETRY` until `next_attempt_at` while the jobs
  behind it carry on. A Kafka partition is consumed in order: a message that has to wait 30 s either blocks its
  partition or has to be copied to retry topics.
- **Replay and inspection** are an `UPDATE` and a `SELECT`. In Kafka, "show me the failed orders of customer 42 and
  send them again" means another store next to it.
- **State you can query.** The admin API, the stats and the Prometheus gauges are queries on `sync_job`.
- **No extra infrastructure** on the order path: the webhook writes the inbox and the job in one local transaction.

So Postgres keeps the work ("this order still has to reach the ERP") and Kafka carries facts ("this order reached
the ERP"), written to the outbox in the same transaction as the state change they describe.
