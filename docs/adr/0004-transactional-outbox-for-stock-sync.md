# 0004. Transactional outbox for the stock sync (ERP -> shop)

- Status: accepted
- Date: 2026-10-13

## Context

The ERP reports stock changes per article and the shop has to show them. Every change touches two systems: we
store the new level, and the shop's stock API has to be called.

Doing both inside the webhook request is the **dual-write problem**:
- Store first, then call the shop: if the call fails or the process dies in between, the shop never hears about the
  change.
- Call the shop first, then store: if the store fails, the shop shows a level we don't have.
- Wrapping the HTTP call in the database transaction does not help either. The call cannot be rolled back, and a
  slow shop would hold a database connection and row locks.

Stock adds two more problems:
- It is an **absolute value** ("120 units"), not a delta. Only the newest value matters.
- Changes can arrive **late or out of order** (ERP retries, replays), and an old quantity must never overwrite a
  newer one in the shop.

## Decision

### Changes stamped in the future are rejected

"Newest `changedAt` wins" has a weak spot. One change stamped far ahead (a wrong ERP clock, a wrong offset) would
be newer than every real change to come, and the SKU would stay frozen until that date. So the webhook rejects,
with `400` and nothing stored, any `changedAt` later than *our* clock plus a tolerance for clock skew
(`ordersync.stock.max-clock-skew`, default 5 minutes). The ERP sees the error and someone fixes its clock.

### Webhook, not polling

The ERP pushes changes to `POST /webhooks/erp/stock`, signed with its own HMAC secret (`ERP_WEBHOOK_SECRET`) and
deduplicated by `X-Erp-Event-Id` in `erp_inbox_event`. It mirrors the shop webhook, and it delivers each change as
soon as it happens. Polling the ERP ("what changed since T?") would also work, and it is the fallback for an ERP
that cannot send webhooks. But it needs a reliable change cursor in the ERP, it adds latency of up to one polling
interval, and it misses intermediate values. The `changedAt` rules below would stay the same.

### One transaction, then a relay

The webhook does everything in **one database transaction**:

1. Insert into `erp_inbox_event` with `ON CONFLICT DO NOTHING`. A redelivery answers `200 {"duplicate": true}`.
2. Upsert `stock_level` **unless the change is older**, in a single statement:
   `INSERT ... ON CONFLICT (article_code) DO UPDATE ... WHERE stock_level.changed_at <= EXCLUDED.changed_at`.
   No row returned means the change is stale. It stays in the inbox (for auditing) and nothing else happens:
   `200 {"stale": true}`. An **equal** `changedAt` is applied, not stale. ERPs often stamp to the second, and
   two changes in the same second (120, then 80) must not lose the second one. Between equal timestamps the arrival
   order decides, and duplicates are already filtered by event id. The price is small: the same body re-sent under
   a new event id is applied again, and the shop gets one more PUT with the same quantity.

   **Known trade-off.** Say the ERP sends 120 and then 80, both stamped 10:05:00, and later re-sends the 120 event
   under a **new** event id. That copy is applied as the latest change and the shop ends with 120. With equal
   timestamps and a new id, nothing in the message says that it is older. If the ERP reuses the event id when it
   re-sends, the inbox drops the copy and this cannot happen. Strict ordering would need a sequence number per SKU
   from the ERP (a version that grows with every change), compared instead of `changedAt`. We don't have that in the
   contract, so we accept this case.
3. Mark the article's unsent outbox messages (`PENDING`, `RETRY`) as `SUPERSEDED`. A message that is in flight
   (`IN_PROGRESS`) is left alone; ordering (below) makes the new one wait for it.
4. Insert the new `outbox_message` (destination `SHOP_STOCK_API`, payload with the quantity and `changedAt`) and
   answer `202`.

If any step fails, nothing is stored and the ERP gets an error, so it retries later. A test injects a failure into
the outbox insert, the last write, and checks that neither the inbox row nor the stock level survives.

The **outbox relay** then delivers the messages with the same mechanics as the order worker (ADR 0001, 0003): a
claim with a lease and `FOR UPDATE SKIP LOCKED`, the HTTP call outside any transaction, a result guarded by the
lease, persistent retries with backoff, an attempt budget, and a `shop` circuit breaker that only counts retryable
failures. Each PUT carries `Idempotency-Key: outbox-<id>`, so a retry of the same message is recognisable as such.
Destinations are pluggable (`OutboxDispatcher`); today there is only the shop's stock API.

### Order per key (head-of-line)

A message is only claimed if no **older** message (lower id) with the same `(aggregate_type, aggregate_id, destination)` is still
`PENDING`, `RETRY` or `IN_PROGRESS`. This is a `NOT EXISTS` in the claim. Only the oldest active message of a key
passes it, so a batch never holds two messages of one key. That avoids sending 80 and then 100 in parallel and
letting the network decide which arrives last.

- A message waiting for a retry blocks the newer messages **of its own SKU only**. This is intended: delivering
  the newer quantity first and then the older retry would leave the shop wrong. Other SKUs are unaffected.
- `DEAD`, `DELIVERED` and `SUPERSEDED` messages do not block, so one broken message does not freeze its SKU forever.
- Id order matches the order in which stock levels were applied, because of step 2: the upsert row-locks the
  article until the webhook transaction commits. A concurrent update of the same article waits, compares its
  `changedAt` with the committed value, and only then inserts its own message, with a higher id.

### Stale check before sending

A replay breaks id order. Suppose message A (quantity 100) failed with a 400 and went `DEAD`, and then message B
(quantity 80, newer) was delivered. Replaying A would send 100 after 80. So before each PUT the dispatcher checks
whether a **newer message (higher id) exists for the same SKU**. If one does, the message is marked `SUPERSEDED`
and not sent. This takes no attempt.

The check uses message order and not `changedAt`. Since equal timestamps are applied, A and B may carry the same
`changedAt`, and only the id tells which one came later.

## Consequences

- No lost updates: a stock change is either fully stored with its outbox message, or not stored at all.
- No out-of-order overwrite in the shop, whether the cause is late webhooks, parallel delivery or replays.
- Delivery is at least once. If a relay dies after the shop accepted a PUT but before recording it, the message is
  sent again when the lease expires. Setting an absolute stock level twice is harmless, and the Idempotency-Key lets
  the shop drop the repeat.
- `SUPERSEDED` messages are never sent but stay visible in `GET /admin/outbox`, together with the reason.
- The outbox table grows. Cleaning up old `DELIVERED` and `SUPERSEDED` rows is left for later.
- A shop that keeps rejecting one SKU leaves that SKU's message `DEAD` until an operator replays it. Newer changes
  for the SKU still go out, because `DEAD` does not block.
