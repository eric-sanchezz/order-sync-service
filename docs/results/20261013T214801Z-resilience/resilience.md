# Resilience run 20261013T214801Z

10 orders/s for 180s with k6 (5% of the requests redeliver an earlier event).
The ERP answered 503 to every call from second 60 to second 120 after k6's first request.
Figures computed from the data once the queue was empty (PENDING + RETRY + IN_PROGRESS = 0): k6's output, the
sync_job table and the ERP mock's request journal.

- Lost: accepted (202) and the ERP never answered a 2xx to its Idempotency-Key, whatever its job says, or no job.
- Duplicated: more than one job for the order, more than one 2xx from the ERP for its key, an ERP key that matches
  no order k6 sent, or one key sent with different payloads (counted once per key).

| Figure | Value |
|---|---|
| New orders sent by k6 / accepted (202) | 1711 / 1711 |
| **Lost** | **0** |
| ... no job / job DEAD without ERP 2xx / job DONE without ERP 2xx | 0 / 0 / 0 |
| **Duplicated** | **0** |
| ... orders with more than one job | 0 |
| ... keys the ERP answered 2xx more than once | 0 |
| ... ERP keys matching no order sent | 0 |
| ... keys sent with different payloads | 0 |
| Jobs DONE / DEAD | 1711 / 0 |
| Jobs not sent by k6 (must be 0) | 0 |
| Orders sent whose key never reached the ERP | 0 |
| Chaos check | passed: 22 ERP 503s in the window (at least 5 required), 22 retries, 1159 calls refused by the breaker |
| Requests to the ERP / per accepted order | 1733 / 1.013 |
| ... answered 503 / 2xx / other | 22 / 1711 / 0 |
| Calls refused by the circuit breaker (never sent; app metric) | 1159 |
| Retries scheduled (app metric) | 22 |
| Max ERP calls used by one job | 3 |
| Ingest latency p95 / max (k6, webhook -> 202) | 5.7 ms / 33.7 ms |
| End to end p50 / p95 / max (accepted -> DONE) | 45927 ms / 86026 ms / 119429 ms |
| k6 requests / failed rate / checks rate / exit code | 1801 / 0 / 1 / 0 |

Latencies and the ratio are rounded up. Raw data in this folder: `k6-summary.json` (k6's own summary),
`k6-output.log`, `sent-orders.txt`, `accepted-orders.txt`, `results.txt` (every figure above),
`environment.txt` and `run.log`.
