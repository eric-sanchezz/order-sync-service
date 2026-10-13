# 0003. Persistent retries instead of in-memory retries, plus a circuit breaker

- Status: accepted
- Date: 2026-10-13

## Context

The ERP fails in different ways. A single 503 or timeout. Minutes of downtime. A clear "no" (4xx) for one order. The
usual tools (Resilience4j `@Retry`, Spring Retry, n8n's "retry on fail") retry **in memory**: the waiting happens
inside the running process.

That has three problems for order sync:

- A restart or deploy loses every pending retry. If we already answered `202` to the shop, the order is lost.
- A thread or execution sits blocked during the backoff. Long backoffs (minutes) become impractical.
- Nobody can see what is waiting, why, or for how long.

## Decision

**Retries live in the database.** A retryable failure (408, 429, 5xx, timeout, IO error) increments `attempts`
and sets `status = 'RETRY'` and `next_attempt_at = now + backoff`. The worker only claims jobs whose
`next_attempt_at` has passed. With `attempts` reaching the maximum (5 calls in total) the job goes `DEAD` instead.

- Backoff: `min(5 min, 2 s * 2^(n-1))`, randomized within `[d/2, d]` (jitter) so that orders which failed together
  do not retry together. It is a pure class with a seedable `RandomGenerator`, unit-tested.
- A non-retryable 4xx goes straight to `DEAD` with `HTTP <code>: <body>`. An invalid order (unknown customer,
  totals that don't match, missing fields, overflow) goes `DEAD` without calling the ERP. Any unexpected exception
  while transforming also ends in `DEAD`, never in an endless retry loop.
- `DEAD` jobs stay visible in the admin API and can be replayed once the cause is fixed.

**A circuit breaker protects the ERP.** Persistent retries alone would still hammer an ERP that is down: every due
job makes a call that is bound to fail. Resilience4j's breaker (`erp`, count-based, window 10, at least 5 calls, 50 %
failure rate, 30 s open, 1 trial call when half-open) sits around the HTTP call:

- Only **retryable** failures count. A 4xx means the ERP is up and rejected one order; it must not open the circuit
  for everyone else.
- When the circuit is open, the call is refused locally. The job goes back to `RETRY` in 30 s **without** using up
  an attempt, because the ERP was never contacted. A long outage therefore does not burn through the attempt budget
  and push healthy orders to `DEAD`.

The two mechanisms split the work: the circuit breaker decides **whether** to call the ERP right now, and the
database decides **when** each job is tried again and remembers it across restarts.

## Consequences

- Retries survive restarts and deploys, and the backoff can be minutes long without blocking anything.
- The state of every order is queryable (`/admin/jobs?status=RETRY`, `/admin/stats`).
- The breaker is in memory and per instance. With several instances each one learns about an outage on its own,
  which is acceptable.
- Timing in tests: the test profile uses 10 ms backoff without jitter, a 20 ms circuit-open delay, and a breaker
  that never opens by itself. Tests force it open explicitly to check the "no attempt used" rule.
