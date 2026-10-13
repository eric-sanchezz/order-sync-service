# Raw results

Each `<UTC timestamp>-resilience/` folder is one run of `./scripts/resilience-run.sh`, kept as it came out.

## How a run works

1. With the compose stack up, the script recreates the app with `CB_WAIT_OPEN=10s` and `CIRCUIT_OPEN_DELAY=5s` (the
   defaults are 30 s each; shorter values let the queue recover within a three-minute run). Everything else keeps the
   defaults of `application.properties`. It refuses to run if the queue (PENDING + RETRY + IN_PROGRESS) is not empty.
2. The ERP mock gets one extra stub that always answers 201 after a uniform 50-800 ms delay. It has priority 2, so
   the demo's `503, 201, 201` scenario (priority 5) never matches during the run; WireMock scenarios are not safe
   under concurrent requests. Then the mock's request journal is emptied (it holds up to 100000 requests).
3. k6 (`grafana/k6:2.3.0`, compose profile `load`) posts signed orders at 10/s for 180 s. Every 20th request
   redelivers the event of ten requests earlier instead of a new order (5%).
4. 60 s after k6's first request, a priority-1 stub that answers 503 to every order is added through WireMock's admin
   API; it is deleted by its id 60 s later. The ERP mock itself keeps running, so its journal is complete. A
   **baseline** run (`CHAOS_DURATION=0`) is the same load with no outage, for comparison.
5. The script waits until the queue is empty (up to 600 s), then computes every figure from data: k6's output
   (orders sent, 202s), the `sync_job` table and the ERP mock's journal. Only "calls refused by the breaker" and
   "retries scheduled" come from the app's own metrics.

Definitions, per shop order, from the ERP's side:
- **Lost**: k6 got a 202 for the order and the ERP never answered a 2xx to its Idempotency-Key (`SHOP-<id>`),
  whatever the state of its job (a `DONE` job without a 2xx in the ERP is lost too), or the order has no job.
- **Duplicated**: more than one job for the shop order id, or more than one 2xx from the ERP for the same key, or a
  key in the ERP that matches no order k6 sent, or the same key sent with different payloads. Counted once per key.
  The UNIQUE index on `sync_job.shop_order_id` (migration V5) already makes two jobs per order impossible.
- An order that never reached the ERP is lost, never duplicated.

A run is INVALID (exit code 1, and not counted) if:
- the queue was not empty before it, or does not drain in time;
- the journal does not return everything it holds, is full, or has a request without key or body;
- k6's accepted lines, unique ids and `accepted` counter disagree;
- a job with one of the run's order ids was not sent by k6;
- with an outage: the ERP answered 503 fewer than 5 times while the chaos stub was in (per the journal's timestamps,
  with 2 s of margin), or there were neither retries nor calls refused by the breaker, or there was a 503 outside
  the window. The breaker needs 5 calls before it can open;
- baseline: the ERP answered 503 at all.

## Worst run

Fixed before the runs: the worst run is the one with the most lost orders, then the most duplicates, then the most
DEAD jobs, then the highest end-to-end p95, among the runs with an outage. The README shows that run next to the
baseline.


## Runs

All on Windows 11 Home + Docker Desktop 4.95 (16 CPUs), one app instance, k6 v2.3.0, with
`RATE=10 DURATION=180 CHAOS_START=60 DRAIN_TIMEOUT=600 MIN_CHAOS_503=5` and `CHAOS_DURATION=60` (outage) or
`CHAOS_DURATION=0` (baseline). Copied from each folder's `results.txt`.

| Run | Outage | Sent / accepted | Lost | Duplicated | DEAD | ERP requests (per order) | 503s in / outside the window | Refused by breaker | Retries | Ingest p95 | End to end p50 / p95 / max |
|---|---|---|---|---|---|---|---|---|---|---|---|
| [20261013T214801Z](20261013T214801Z-resilience/resilience.md) | 60-120 s | 1711 / 1711 | 0 | 0 | 0 | 1733 (1.013) | 22 / 0 | 1159 | 22 | 5.7 ms | 45927 / 86026 / 119429 ms |
| [20261013T215214Z](20261013T215214Z-resilience/resilience.md) (worst, in the README) | 60-120 s | 1711 / 1711 | 0 | 0 | 0 | 1732 (1.013) | 21 / 0 | 1158 | 21 | 6 ms | 46598 / 86498 / 119786 ms |
| [20261013T215628Z](20261013T215628Z-resilience/resilience.md) | 60-120 s | 1711 / 1711 | 0 | 0 | 0 | 1733 (1.013) | 22 / 0 | 1159 | 22 | 6 ms | 45551 / 85685 / 119000 ms |
| [20261013T220039Z](20261013T220039Z-resilience/resilience.md) (baseline, in the README) | none | 1711 / 1711 | 0 | 0 | 0 | 1711 (1) | 0 / 0 | 0 | 0 | 6.3 ms | 1332 / 2202 / 2558 ms |

The runs used `resilience-run.sh`, `analyze.sql` and `orders.js` exactly as published here (the environment files
say `c82f940 (with uncommitted changes)` because H4 was not committed yet). No run was invalid. Three earlier runs,
made with a previous version of the analysis whose definitions of lost and duplicated were weaker, were deleted and
are not counted, as were the short runs used to test the script.
