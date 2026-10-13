# 0005. Observability: gauges from a database snapshot, DEGRADED health, ECS logs, separate management port

- Status: accepted
- Date: 2026-10-13

## Context

An integration that retries quietly in the background is only trustworthy if you can see it working. You want to
know how many orders are waiting or dead, how fast the ERP answers, whether a circuit breaker is open, and you want
to follow one order through the logs.

## Decision

### Queue numbers are gauges read from Postgres, through a snapshot

`ordersync_jobs{status}`, `ordersync_outbox_messages{status}` and `ordersync_jobs_oldest_pending_age_seconds` come
from the database. We did not keep in-memory counters that go up on insert and down on completion:
- The queue lives in Postgres, so the database is the source of truth. In-memory counts start at zero after a
  restart and drift with every edge case (a lost lease, a replay, a redelivery that resets a job).
- With several instances, each one would only know its own part. The database gives every instance the same, total
  answer.
- "Age of the oldest pending job" is the alarm that matters for a queue (something is stuck), and it can only come
  from the data.

But a scrape **never queries the database**. A first version did, and when the database was down or the connection
pool exhausted, each gauge waited up to the pool's 30 s connection timeout. The scrape then took minutes, Prometheus
marked the target down, and every metric was lost, including the circuit breakers and ERP timers that matter most
in an incident. Now:
- A background task (`ordersync.metrics.db-refresh-interval`, default 15 s) reads every gauge in **one** grouped
  query and stores an in-memory snapshot; the gauges read the snapshot, so a scrape costs no I/O.
- The task has its **own one-connection pool**, with a short connection wait and statement timeout
  (`ordersync.metrics.db-query-timeout`, default 2 s). It can neither hang nor take connections from the application.
- If a refresh fails, the previous snapshot stays (and a warning is logged). `ordersync_db_snapshot_age_seconds`
  (seconds since the last successful refresh) tells how old it is, and the dashboard shows it as a panel that turns
  orange at 30 s and red at 60 s. Before the first successful refresh, the counts are NaN (unknown), not 0.
- The worker, the outbox relay and this refresh each get a scheduler thread
  (`spring.task.scheduling.pool.size=3`), so a worker stuck waiting for a connection does not delay the refresh,
  or the relay.

Things that happen, not states, are **counters and timers** in memory: ERP and shop calls by outcome (with latency
histograms, so Prometheus can compute p50/p95 across instances), retries, jobs gone DEAD, and webhooks by source and
result. Losing them on restart is fine: Prometheus works with rates. The tests check them as a difference
before/after, because the Spring context (and its registry) is shared and never reset.

The `erp` and `shop` circuit breakers are built in code from one `CircuitBreakerRegistry` bean.
`spring-cloud-circuitbreaker-resilience4j` binds that registry to Micrometer, so their state is published as
`resilience4j_circuitbreaker_state{name,state}`. A test fails if a breaker is ever built outside that registry.

### DEGRADED, not DOWN

The `erp` and `shop` health components read their circuit breaker; they don't ping anything. `CLOSED` is `UP`.
Every other state (`OPEN`, `HALF_OPEN`, `FORCED_OPEN`, `METRICS_ONLY`, `DISABLED`) is a custom `DEGRADED` status,
ordered between `DOWN` and `UP` and answered with 200.

An unavailable ERP is exactly the situation this service is built for: it keeps accepting webhooks and queueing
work, which is then delivered when the ERP comes back. Reporting `DOWN` would let a load balancer or orchestrator
stop traffic or restart the container, losing the very buffer we want. For the same reason the probes are split:
**liveness** only checks that the process answers (the Docker healthcheck uses it), **readiness** checks the
database, and neither looks at the breakers.

### Separate management port

The actuator runs on port 8081 (`management.server.port`) and serves only `/actuator/health/**` and
`/actuator/prometheus`, without authentication. docker-compose does not publish 8081: only Prometheus, inside the
compose network, can reach it. The application port has no actuator at all. Two layers keep the management port
small: the exposure list (`health,prometheus`) and a security filter chain for the management server that permits
only those two endpoints and denies the rest. Health details are shown in full there, since the port is internal.

### Logs: ECS JSON with correlation ids

Spring Boot's built-in structured logging (`logging.structured.format.console=ecs`, turned on in docker-compose
through `LOGGING_STRUCTURED_FORMAT_CONSOLE`) writes one JSON object per line in Elastic Common Schema. No extra
library and no custom encoder, and any log stack that knows ECS can read it.

Correlation ids travel in the MDC, so they become JSON fields:
- the webhooks set `eventId` for the request;
- the worker sets `eventId` and `jobId`, and the relay sets `outboxId` and `aggregateId`, **inside the virtual thread
  that processes each item**. The MDC is per thread, so setting it in the polling thread would not reach the item.

Each one is removed in a `finally`. Locally and in tests the logs stay plain text; the test log pattern prints the
MDC fields, so a test can check that they are really there.

## Consequences

- Grafana (provisioned datasource and dashboard, anonymous read-only) shows the state of the system right after
  `docker compose up`, with no manual setup.
- Each refresh costs one grouped count query. The queue numbers can be up to one refresh interval (15 s) old, which
  is fine for a dashboard. The snapshot age shows when they are older than that.
- While the database is down, the snapshot is frozen, and so is `ordersync_jobs_oldest_pending_age_seconds`: an
  alert like "oldest pending job > 5 min" would not fire. Any alert on a snapshot gauge has to be paired with one on
  `ordersync_db_snapshot_age_seconds`.
- Nothing about an integration's health can restart the service. Alerting on `DEGRADED` (or on
  `resilience4j_circuitbreaker_state`) is how someone finds out the ERP is down.
