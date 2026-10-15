# 0006. Domain events to Kafka through the transactional outbox

- Status: accepted (revised 2026-10-15: no DEAD on retryable failures, DEAD blocks its key, per-aggregate sequence)
- Date: 2026-10-14

## Context

Other systems (reporting, the warehouse, notifications) want to know what happened: an order reached the ERP, an
order failed for good, a stock level changed. Kafka is the place for that: durable, replayable by the consumers,
many independent readers.

Publishing straight from the code that changes the state is the dual-write problem again (ADR 0004):
- Commit the job as `DONE`, then send to Kafka: if the send fails or the process dies in between, the event is lost
  and the database and the event stream disagree for good.
- Send first, then commit: a rollback leaves an event about something that did not happen.
- Sending inside the database transaction holds a connection and row locks while waiting on the network, and still
  cannot be rolled back.

## Decision

- **Events go through the outbox we already have.** `DomainEvents` writes a row with destination `KAFKA` in the
  transaction of the state change (its methods use `Propagation.MANDATORY`, so they cannot run outside one):
  - `OrderSynced`: the guarded `DONE` update and the event, in one transaction;
  - `OrderFailed`: every path to `DEAD` (ERP rejection, attempts used up, invalid order, unknown customer, expired
    leases, results the database rejects) goes through one method that does the same;
  - `StockUpdated`: in the stock webhook's transaction, next to the shop's message.

  If the guard matches no row (the lease was lost), no event is written. If writing the event fails, the status
  change is rolled back with it, and the job is picked up again when its lease expires.
- **`KafkaDispatcher` is one more `OutboxDispatcher`.** The relay claims, retries with backoff and replays as for the
  shop. It sends with `KafkaTemplate.send(...).get(timeout)` and only marks the message `DELIVERED` once Kafka has
  acknowledged it.
- **Order per key.** The record key is the aggregate id: the shop order id for order events, the article code for
  stock events. One key always lands in the same partition, and the relay's head-of-line claim (ADR 0004) never
  sends an event while an older one of the same key is pending or in flight. Topics `ordersync.orders.v1` and
  `ordersync.stock.v1`, 3 partitions, created at startup.
- **A Kafka outage never sends an event DEAD.** Retryable failures (broker away, timeouts, not enough replicas,
  leader changes) are retried with the usual backoff (2 s doubling, capped at 5 min) with no attempt limit
  (`OutboxDispatcher.retriesUntilDelivered()`); an expired lease does not count against a limit either. A circuit
  breaker `kafka` (same settings as `erp` and `shop`, only retryable failures count) stops calling Kafka while it is
  open, and the refused calls do not use up attempts (as D2 for the ERP). Only failures a retry cannot fix (record
  too large, serialization, invalid topic, authorization or authentication) send an event `DEAD`.
- **A `DEAD` event blocks its key.** For `KAFKA`, the claim treats a `DEAD` message like a pending one: newer events of
  the same key wait until it is replayed. A key is the aggregate type plus its id: order 1001 and a SKU "1001" do
  not block each other. The shop keeps the old rule (a `DEAD` stock message does not block),
  because the stock level is absolute and the staleness guard drops a late replay; an event, instead, is a fact a
  consumer cannot "undo" when it arrives late.
- **A per-aggregate `sequence`.** Each event carries 1, 2, 3... per aggregate, from `event_sequence`: an upsert
  (`INSERT ... ON CONFLICT DO UPDATE SET last_sequence = last_sequence + 1 RETURNING`) in the event's transaction,
  before the outbox insert. The row lock makes a second writer of the aggregate wait until the first commits (or
  rolls back and gives its number back), so the sequence follows the commit order, and the outbox id taken right
  after follows it too: publication order and sequence agree.
- **SUPERSEDED and the staleness guard do not apply to `KAFKA`.** The shop needs the latest stock level; a consumer
  of `StockUpdated` may need every change. Events are history, not state.
- **Producer**: `acks=all` (an event counts as sent only once every in-sync replica has it) and
  `enable.idempotence=true` (the broker drops the producer's own retries of a batch). Short timeouts
  (`max.block.ms` 5 s, `delivery.timeout.ms` 10 s) so a missing broker frees the relay thread quickly; the relay is
  the one that retries.
- **Envelope** (JSON, version 1): `eventId` (UUID), `type`, `aggregateId`, `sequence`, `occurredAt`, `version`,
  `correlationId` (the webhook event id that started it) and `payload`. Headers: `eventId`, `eventType`,
  `correlationId`. Fields are only ever added; a breaking change gets a `.v2` topic. An order that arrived without an
  id has no shop order id; its events use `job-<job id>` as the key.
- `ordersync.events.kafka.enabled=false` stops writing events (the shared test context uses it, and so can a
  deployment without Kafka); messages already in the outbox are still published.

## Consequences

- **At least once, not exactly once.** If Kafka stores an event but the acknowledgement does not arrive in time,
  the relay sends it again. Idempotence does not cover this: it is a new `send()`, not a retry of the same batch.
  Consumers deduplicate by `eventId`, and drop any event whose `sequence` is not higher than the last one they saw
  for that aggregate (a replayed event keeps its sequence). Seen in real runs: with Kafka paused for about 40 s and
  for 97 s, an `OrderSynced` appeared twice at consecutive offsets, with the same `eventId` and `sequence`.
- **After a long outage, publishing resumes at the pace of the backoff.** In the 97 s run the event had failed 6
  times, the breaker was open, and it went out 57 s after Kafka was back, when its next retry was due. With the 5 min
  cap the wait can be up to 5 minutes. It shows as lag (`ordersync_events_outbox_oldest_age_seconds`), not as loss.
- **A `DEAD` event stops its key until someone acts.** Newer events of that order or SKU pile up in `PENDING`
  (visible in the lag and in `ordersync_events_blocked_keys`). This is the intended trade: a stuck key is visible and
  fixable; an event published out of order would leave consumers on a wrong state without anyone noticing. Alert on
  `ordersync_events_blocked_keys > 0` for 5 minutes, on the lag above a few minutes, and on the `kafka` breaker not
  being `closed`. With several `DEAD` events in one key, replay the oldest first: the newer ones wait behind it.
  An event that can never be published (for example an unknown event type left by a bug) goes `DEAD` again on every
  replay and keeps its key blocked; today the only way out is SQL (mark it `SUPERSEDED` with a note), a decision for
  an operator, not for the relay.
- Publishing lag is at least the relay's poll interval (1 s) and grows with the outbox backlog.
- One more table (`event_sequence`, a row per aggregate that ever had an event) and one more row lock per event.
  Writers of the same aggregate are already serialized by the job's lease or the stock level's row lock, so the
  lock adds no waiting in practice.
- The work queue stays in Postgres (ADR 0001, updated): Kafka carries facts, not work.
