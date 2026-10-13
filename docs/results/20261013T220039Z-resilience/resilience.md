# Resilience run 20261013T220039Z

10 orders/s for 180s with k6 (5% of the requests redeliver an earlier event).
Baseline: no ERP outage.
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
| Chaos check | baseline run: no outage, 0 ERP 503s |
| Requests to the ERP / per accepted order | 1711 / 1 |
| ... answered 503 / 2xx / other | 0 / 1711 / 0 |
| Calls refused by the circuit breaker (never sent; app metric) | 0 |
| Retries scheduled (app metric) | 0 |
| Max ERP calls used by one job | 1 |
| Ingest latency p95 / max (k6, webhook -> 202) | 6.3 ms / 94.8 ms |
| End to end p50 / p95 / max (accepted -> DONE) | 1332 ms / 2202 ms / 2558 ms |
| k6 requests / failed rate / checks rate / exit code | 1800 / 0 / 1 / 0 |

Latencies and the ratio are rounded up. Raw data in this folder: `k6-summary.json` (k6's own summary),
`k6-output.log`, `sent-orders.txt`, `accepted-orders.txt`, `results.txt` (every figure above),
`environment.txt` and `run.log`.
