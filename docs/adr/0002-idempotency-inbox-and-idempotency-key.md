# 0002. Idempotency in two layers: inbox table and Idempotency-Key

- Status: accepted
- Date: 2026-10-13

## Context

Duplicates can appear at two points:

1. **Shop -> us.** Webhooks are delivered at least once. The shop retries if we answer slowly or the connection
   drops, so the same order can arrive several times.
2. **Us -> ERP.** If the ERP call times out, we cannot know whether the ERP created the order. Retrying is the
   only safe option, but a naive retry can create the order twice.

## Decision

**Layer 1: inbox.** Every webhook carries `X-Shop-Event-Id`. In one transaction, the webhook runs
`INSERT INTO inbox_event ... ON CONFLICT (event_id) DO NOTHING RETURNING id`. If the insert happens, a `PENDING`
job is created and we answer `202`. If not, we look up the existing job and answer `200 {"duplicate": true}`. The
UNIQUE constraint does the work, so two concurrent deliveries of the same event cannot both win.

Order of checks: HMAC signature, then event id (1-200 characters), then JSON readability, then storage. Nothing is
stored for a request that fails any check.

**Layer 2: Idempotency-Key.** Every ERP call sends `Idempotency-Key: <event_id>` (and the same value as
`X-Correlation-Id`). An ERP that honours the header returns the original result for a repeated key instead of
creating a new order. Retries after a timeout, and a job re-claimed after a lost lease (ADR 0001), are safe.

## The ERP contract is invented

The ERP API (`POST /api/v1/sales-orders`, its JSON and its use of `Idempotency-Key`) is invented for this project.
A real ERP may not support idempotency keys at all. The fallback would then be a lookup by `externalReference`
(`SHOP-<order id>`) before creating, which is slower and racy.

## Risk: replay reuses the key

A manual replay of a `DEAD` job sends the same `Idempotency-Key` again. That is what you want when the earlier
attempts never created the order (a 4xx, or a run of 5xx). But if the ERP rejected the order (e.g. `422`) and
**remembers that answer under the key**, the replay gets the same `422` even after the data was fixed. Real APIs
differ: some only store successful responses, some store everything for 24 h. With such an ERP, the replay would
need a new key (for example `<event_id>-replay-<n>`). We keep the plain event id because it is the safest choice
against duplicates, and we document the trade-off here.

## When the ERP answered but the result cannot be written

The ERP call and the database write that records its result cannot be one transaction. If the write fails after
the ERP has answered, the error decides what happens:

- **Transient** (statement timeout or cancel, lost connection, deadlock, pool exhausted). The worker gives up on
  this job and it stays `IN_PROGRESS`. When the 60 s lease expires another claim picks it up, counts one attempt
  (ADR 0001), and calls the ERP again **with the same `Idempotency-Key`**. An idempotent ERP returns the original
  result, which is now stored normally. Nothing is lost and nothing is duplicated, and the attempt budget bounds the
  loop if the database keeps failing. Sending such a job to `DEAD` would be worse: an order the ERP already created
  would need a manual replay for a hiccup that fixes itself.
- **The data is rejected** (`DataIntegrityViolationException`: a constraint, a value that cannot be represented).
  Writing it again would fail after every lease expiry and re-send the order each time. The job goes `DEAD` at
  once, with a fixed message that does not carry the offending value (so that write cannot fail the same way):
  *"result could not be stored (...). Check the ERP before replaying this job."*

We only treat data rejections as permanent, and no other "non-transient" error. Spring classifies a dropped connection
(`DataAccessResourceFailureException`) and a broken SQL statement after a bad deploy as non-transient. For an order
both are temporary, and the attempt budget still ends them in `DEAD` if they persist.

Known values from the outside are made storable beforehand, so that this path is a last resort:
- `erp_order_id` is `text` (migration V3). The ERP owns that id and we had no reason to cap it at 100 characters:
  with the cap, a valid answer became a `DEAD` job that could never be replayed to `DONE`, because an idempotent ERP
  sends back the same id every time.
- U+0000, which Postgres text cannot hold, is written as `<NUL>` in `last_error` and `erp_order_id`.

## Consequences

- At-least-once delivery from the shop plus idempotent processing gives effectively-once orders in the ERP,
  provided the ERP honours the key.
- The inbox keeps the raw payload, so the admin API can show exactly what the shop sent.
- The inbox grows forever. A retention job (delete events older than N days whose job is DONE) is left for later.
