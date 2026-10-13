#!/usr/bin/env bash
# Resilience run: steady load of signed orders with k6 while the ERP goes down for a minute, then checks, from the
# data, that every accepted order reached the ERP exactly once.
#
#   docker compose up --build -d      # once
#   ./scripts/resilience-run.sh
#
# Env (defaults in brackets): RATE [10] orders/s, DURATION [180] s of load, CHAOS_START [60] s after k6's first
# request, CHAOS_DURATION [60] s of ERP outage (0: baseline run, no outage), DRAIN_TIMEOUT [600] s to wait for the
# queue to empty, MIN_CHAOS_503 [5] ERP 503s the outage must cause for the run to count.
# Needs: docker (with compose v2), curl. Writes docs/results/<UTC timestamp>-resilience/.
# Exit code: 0 for a complete run (whatever its figures), 1 if the run is not valid (see INVALID in resilience.md).
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: do not rewrite /container/paths passed to docker
# (so curl gets no "-o /dev/null" here: native Windows tools would not understand it; shell redirections are used)

RATE="${RATE:-10}"
DURATION="${DURATION:-180}"
CHAOS_START="${CHAOS_START:-60}"
CHAOS_DURATION="${CHAOS_DURATION:-60}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-600}"
MIN_CHAOS_503="${MIN_CHAOS_503:-5}" # the breaker needs 5 calls before it can open
JOURNAL_CAPACITY=100000 # --max-request-journal-entries of erp-mock in docker-compose.yml
ERP="http://localhost:8089"

for tool in docker curl; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done
cd "$(dirname "$0")/.."

RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID="$RUN_TS"
ORDER_BASE="$(date +%s)000"                             # unique shop order ids for this run
ORDER_MAX=$((ORDER_BASE + RATE * DURATION * 2))
RESULTS="docs/results/${RUN_TS}-resilience"
mkdir -p "$RESULTS"
log() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$RESULTS/run.log"; }
sql() { docker compose exec -T postgres psql -U ordersync -d ordersync -At -c "$1"; }
queue_size() { sql "SELECT count(*) FROM sync_job WHERE status IN ('PENDING', 'RETRY', 'IN_PROGRESS')"; }
# Sum of an app metric, read straight from the app's management port (not published: through the prometheus
# container, which is in the same network), so there is no scrape delay.
metric() {
  docker compose exec -T prometheus wget -qO- http://app:8081/actuator/prometheus \
    | awk -v pattern="$1" '$0 !~ /^#/ && $0 ~ pattern { sum += $NF } END { printf "%d\n", sum }'
}

stub_id() { tr -d '\r\n' | sed -nE 's/^[^"]*"id" *: *"([^"]+)".*/\1/p'; }
# WireMock admin calls: fail loudly, retry transient connection errors.
admin() { curl -sS -f --retry 3 --retry-delay 1 --retry-all-errors "$@"; }
# Every stub this script creates carries this metadata, so the ones left behind by an interrupted run can be removed.
remove_run_stubs() {
  curl -s -X POST "$ERP/__admin/mappings/remove-by-metadata" -H 'Content-Type: application/json' \
    -d '{ "matchesJsonPath": "$.resilienceRun" }' > /dev/null
}
CHAOS_PID=""
cleanup() {
  [ -n "$CHAOS_PID" ] && kill "$CHAOS_PID" 2> /dev/null || true
  # Back to the demo behaviour of the README ("Try it"): only this script's stubs are removed, so the file mappings
  # (503, 201, 201 cycle) answer again, from the start of their cycle.
  remove_run_stubs || true
  curl -s -X POST "$ERP/__admin/scenarios/reset" > /dev/null || true
  unset CB_WAIT_OPEN CIRCUIT_OPEN_DELAY
  docker compose up -d --wait app > /dev/null 2>&1 || true
}
trap cleanup EXIT
invalid() { # the run cannot be used: say why in resilience.md and exit 1
  log "INVALID RUN: $*"
  printf '# Resilience run %s: INVALID\n\n%s\n\nDo not use any figure of this run.\n' "$RUN_TS" "$*" \
    > "$RESULTS/resilience.md"
  exit 1
}

if [ "$CHAOS_DURATION" -gt 0 ]; then
  log "run $RUN_ID: $RATE orders/s for ${DURATION}s, ERP down ${CHAOS_START}s after the first request for ${CHAOS_DURATION}s"
else
  log "run $RUN_ID: $RATE orders/s for ${DURATION}s, baseline (no ERP outage)"
fi

# 1. Stack up, with the load profile: shorter breaker wait so the queue recovers within the run.
export CB_WAIT_OPEN=10s CIRCUIT_OPEN_DELAY=5s
docker compose up -d --wait > /dev/null
log "stack up (app with CB_WAIT_OPEN=$CB_WAIT_OPEN, CIRCUIT_OPEN_DELAY=$CIRCUIT_OPEN_DELAY)"
# Jobs of an earlier run still retrying would call the ERP with their keys during this run.
before="$(queue_size)"
[ "$before" = "0" ] || invalid "the queue was not empty before the run ($before jobs PENDING, RETRY or IN_PROGRESS)"

# 2. ERP mock for load: one stub, always 201 after 50-800 ms, with a higher priority (2) than the demo's scenario
#    stubs (5, the default), which therefore never match during the run. No WireMock scenario: scenarios are not
#    safe under concurrent requests (they answer 404 "not matched"). Empty journal, so it holds exactly this run.
remove_run_stubs
LOAD_ID="$(admin -X POST "$ERP/__admin/mappings" -H 'Content-Type: application/json' -d '{
  "priority": 2,
  "request": { "method": "POST", "url": "/api/v1/sales-orders" },
  "response": {
    "status": 201,
    "headers": { "Content-Type": "application/json" },
    "body": "{\"orderId\":\"ERP-{{randomValue length=10 type='\''NUMERIC'\''}}\"}",
    "delayDistribution": { "type": "uniform", "lower": 50, "upper": 800 }
  },
  "metadata": { "resilienceRun": "load" }
}' | stub_id)"
[ -n "$LOAD_ID" ] || invalid "could not create the ERP load stub"
admin -X DELETE "$ERP/__admin/requests" > /dev/null
log "ERP mock: 201 stub $LOAD_ID, journal reset"

CUT_BEFORE="$(metric '^ordersync_erp_requests_seconds_count\\{.*outcome="circuit_open"')"
RETRIES_BEFORE="$(metric '^ordersync_jobs_retries_total')"

# 3. Chaos in the background, timed from k6's first request: a higher-priority 503 stub, removed after
#    CHAOS_DURATION seconds by its id. Its window (epoch ms) is kept to check, from the journal, that it had an effect.
if [ "$CHAOS_DURATION" -gt 0 ]; then
(
  waited=0
  until grep -q 'SENT ' "$RESULTS/k6-output.log" 2> /dev/null; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -lt 600 ] || exit 1 # k6 sent nothing in 120 s: no chaos, and the run is reported invalid
  done
  echo "$(date -u +%H:%M:%S) k6 first request" >> "$RESULTS/run.log"
  first=$(date +%s%3N)
  sleep "$CHAOS_START"
  on=$(date +%s%3N) # taken before the stub goes in and (off) after it is gone: the window covers all of it
  id="$(admin -X POST "$ERP/__admin/mappings" -H 'Content-Type: application/json' -d '{
    "priority": 1,
    "request": { "method": "POST", "url": "/api/v1/sales-orders" },
    "response": { "status": 503, "headers": { "Content-Type": "application/json" },
                  "jsonBody": { "error": "ERP down (chaos)" } },
    "metadata": { "resilienceRun": "chaos" }
  }' | stub_id)"
  echo "$(date -u +%H:%M:%S) chaos ON (stub $id)" >> "$RESULTS/run.log"
  sleep "$CHAOS_DURATION"
  admin -X DELETE "$ERP/__admin/mappings/$id" > /dev/null
  off=$(date +%s%3N)
  echo "$(date -u +%H:%M:%S) chaos OFF (stub $id deleted)" >> "$RESULTS/run.log"
  printf 'chaos_window_s=%s-%s\nchaos_on_ms=%s\nchaos_off_ms=%s\n' \
    "$(((on - first) / 1000))" "$(((off - first) / 1000))" "$on" "$off" > "$RESULTS/chaos.txt"
) &
CHAOS_PID=$!
else
  printf 'chaos_window_s=none\nchaos_on_ms=0\nchaos_off_ms=0\n' > "$RESULTS/chaos.txt"
fi

# 4. Load.
log "k6 starts"
K6_EXIT=0
docker compose --profile load run --rm \
  -e RUN_ID="$RUN_ID" -e ORDER_BASE="$ORDER_BASE" -e RATE="$RATE" -e DURATION="${DURATION}s" \
  -e SUMMARY_PATH="/results/${RUN_TS}-resilience/k6-summary.json" \
  k6 run --quiet /scripts/orders.js > "$RESULTS/k6-output.log" 2>&1 || K6_EXIT=$?
log "k6 finished (exit code $K6_EXIT; 99 means a threshold failed)"
[ -z "$CHAOS_PID" ] || wait "$CHAOS_PID" || true
CHAOS_PID=""
[ -s "$RESULTS/chaos.txt" ] || invalid "the chaos stub was not injected and removed (see run.log)"
[ -s "$RESULTS/k6-summary.json" ] || invalid "k6 wrote no summary (exit code $K6_EXIT, see k6-output.log)"

# 5. Wait until the queue is empty: only then does "no DONE job" mean "lost".
log "waiting for the queue to drain (up to ${DRAIN_TIMEOUT}s)"
deadline=$((SECONDS + DRAIN_TIMEOUT))
while :; do
  pending="$(queue_size)"
  [ "$pending" = "0" ] && break
  [ "$SECONDS" -lt "$deadline" ] || invalid "the queue still had $pending jobs after ${DRAIN_TIMEOUT}s"
  sleep 2
done
log "queue drained"
CUT_AFTER="$(metric '^ordersync_erp_requests_seconds_count\\{.*outcome="circuit_open"')"
RETRIES_AFTER="$(metric '^ordersync_jobs_retries_total')"

# 6. Results, from the data: k6's sent and accepted orders, the database and the ERP's request journal.
grep -o 'SENT [0-9]*' "$RESULTS/k6-output.log" | cut -d' ' -f2 | sort -u > "$RESULTS/sent-orders.txt"
grep -o 'ACCEPTED [0-9]*' "$RESULTS/k6-output.log" | cut -d' ' -f2 | sort -u > "$RESULTS/accepted-orders.txt"
ACCEPTED_LINES="$(grep -c 'ACCEPTED [0-9]' "$RESULTS/k6-output.log" || true)"
WORK="$RESULTS/.work" # inside the repo: a relative path works for docker on Windows too
mkdir -p "$WORK"
admin "$ERP/__admin/requests" | tr -d '\r\n' > "$WORK/journal.json"
tr -d '\r\n' < "$RESULTS/k6-summary.json" > "$WORK/k6-summary.json"
docker compose exec -T postgres mkdir -p /tmp/resilience
for file in "$RESULTS/sent-orders.txt:sent.txt" "$RESULTS/accepted-orders.txt:accepted.txt" \
            "$WORK/journal.json:journal.json" "$WORK/k6-summary.json:k6-summary.json"; do
  docker compose cp "${file%%:*}" "postgres:/tmp/resilience/${file##*:}" > /dev/null 2>&1
done
chaos_value() { sed -n "s/^$1=//p" "$RESULTS/chaos.txt"; }
docker compose exec -T postgres psql -U ordersync -d ordersync -q \
  -v order_base="$ORDER_BASE" -v order_max="$ORDER_MAX" \
  -v chaos_on_ms="$(chaos_value chaos_on_ms)" -v chaos_off_ms="$(chaos_value chaos_off_ms)" \
  -f - < scripts/resilience/analyze.sql > "$RESULTS/results.txt"
rm -rf "$WORK"
{
  echo "accepted_lines_in_k6_output=$ACCEPTED_LINES"
  echo "breaker_cut_calls=$((CUT_AFTER - CUT_BEFORE))"
  echo "retries_scheduled=$((RETRIES_AFTER - RETRIES_BEFORE))"
  grep '^chaos_window_s=' "$RESULTS/chaos.txt"
  echo "k6_exit_code=$K6_EXIT"
} >> "$RESULTS/results.txt"
value() { sed -n "s/^$1=//p" "$RESULTS/results.txt"; }

# Consistency of the inputs: if one fails, the figures cannot be trusted.
grep -qE '^[a-z0-9_]+=$' "$RESULTS/results.txt" && invalid "empty figures in results.txt: $(grep -E '^[a-z0-9_]+=$' "$RESULTS/results.txt" | tr '\n' ' ')"
[ "$(value journal_total)" = "$(value journal_returned)" ] \
  || invalid "the ERP journal returned $(value journal_returned) of $(value journal_total) requests"
[ "$(value journal_total)" -lt "$JOURNAL_CAPACITY" ] || invalid "the ERP journal reached its capacity ($JOURNAL_CAPACITY)"
[ "$(value erp_requests_without_key_or_body)" = "0" ] || invalid "ERP requests without Idempotency-Key or body in the journal"
[ "$ACCEPTED_LINES" = "$(value accepted_orders)" ] && [ "$ACCEPTED_LINES" = "$(value k6_accepted_counter)" ] \
  || invalid "k6 accepted lines ($ACCEPTED_LINES), unique ids ($(value accepted_orders)) and counter ($(value k6_accepted_counter)) differ"
[ "$(value jobs_not_sent_by_k6)" = "0" ] \
  || invalid "$(value jobs_not_sent_by_k6) jobs with this run's order ids were not sent by k6 (jobs_not_sent_by_k6)"
# The outage must show in the data: enough 503s from the ERP while the chaos stub was in, none outside it, and
# retries or calls refused by the breaker. A baseline run must have no 503 at all.
if [ "$CHAOS_DURATION" -gt 0 ]; then
  [ "$(value erp_requests_503_in_chaos_window)" -ge "$MIN_CHAOS_503" ] \
    || invalid "the chaos had no measurable effect: $(value erp_requests_503_in_chaos_window) ERP 503s in its window (at least $MIN_CHAOS_503 required)"
  [ "$(value retries_scheduled)" -gt 0 ] || [ "$(value breaker_cut_calls)" -gt 0 ] \
    || invalid "the chaos had no measurable effect: no retries and no calls refused by the circuit breaker"
  [ "$(value erp_requests_503_outside_chaos_window)" = "0" ] \
    || invalid "$(value erp_requests_503_outside_chaos_window) ERP 503s outside the chaos window: the ERP mock did not behave as configured"
  CHAOS_CHECK="passed: $(value erp_requests_503_in_chaos_window) ERP 503s in the window (at least $MIN_CHAOS_503 required), $(value retries_scheduled) retries, $(value breaker_cut_calls) calls refused by the breaker"
  CHAOS_TEXT="The ERP answered 503 to every call from second $(value chaos_window_s | cut -d- -f1) to second $(value chaos_window_s | cut -d- -f2) after k6's first request."
else
  [ "$(value erp_requests_503)" = "0" ] || invalid "baseline run, but the ERP answered 503 $(value erp_requests_503) times"
  CHAOS_CHECK="baseline run: no outage, 0 ERP 503s"
  CHAOS_TEXT="Baseline: no ERP outage."
fi

# 7. Environment.
windows="$(powershell.exe -NoProfile -Command '(Get-CimInstance Win32_OperatingSystem).Caption + " " + (Get-CimInstance Win32_OperatingSystem).Version' 2> /dev/null | tr -d '\r' || true)"
{
  echo "date_utc=$RUN_TS"
  echo "git_commit=$(git rev-parse --short HEAD)$(git diff --quiet && git diff --cached --quiet || echo ' (with uncommitted changes)')"
  echo "host_os=${windows:-$(uname -s) $(uname -r)}"
  echo "host_cpus=$(nproc 2> /dev/null || echo unknown)"
  echo "docker=$(docker version --format '{{.Server.Platform.Name}}, engine {{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})')"
  echo "docker_vm=$(docker info --format '{{.OperatingSystem}}, {{.NCPU}} CPUs, {{.MemTotal}} bytes of memory')"
  echo "k6=$(docker compose --profile load run --rm --no-deps k6 version 2> /dev/null | head -1)"
  echo "app_instances=1"
  echo "command=RATE=$RATE DURATION=$DURATION CHAOS_START=$CHAOS_START CHAOS_DURATION=$CHAOS_DURATION DRAIN_TIMEOUT=$DRAIN_TIMEOUT MIN_CHAOS_503=$MIN_CHAOS_503 ./scripts/resilience-run.sh"
  echo "app_overrides=CB_WAIT_OPEN=10s CIRCUIT_OPEN_DELAY=5s (everything else: defaults of application.properties)"
  echo "erp_mock=WireMock 3.13.1, one priority-2 stub answering 201 after uniform 50-800 ms; chaos = priority-1 stub answering 503"
} > "$RESULTS/environment.txt"

cat > "$RESULTS/resilience.md" <<EOF
# Resilience run $RUN_TS

$RATE orders/s for ${DURATION}s with k6 (5% of the requests redeliver an earlier event).
$CHAOS_TEXT
Figures computed from the data once the queue was empty (PENDING + RETRY + IN_PROGRESS = 0): k6's output, the
sync_job table and the ERP mock's request journal.

- Lost: accepted (202) and the ERP never answered a 2xx to its Idempotency-Key, whatever its job says, or no job.
- Duplicated: more than one job for the order, more than one 2xx from the ERP for its key, an ERP key that matches
  no order k6 sent, or one key sent with different payloads (counted once per key).

| Figure | Value |
|---|---|
| New orders sent by k6 / accepted (202) | $(value sent_orders) / $(value accepted_orders) |
| **Lost** | **$(value lost)** |
| ... no job / job DEAD without ERP 2xx / job DONE without ERP 2xx | $(value lost_without_job) / $(value lost_dead) / $(value lost_done_without_erp_2xx) |
| **Duplicated** | **$(value duplicated)** |
| ... orders with more than one job | $(value orders_with_more_than_one_job) |
| ... keys the ERP answered 2xx more than once | $(value erp_keys_with_more_than_one_2xx) |
| ... ERP keys matching no order sent | $(value erp_keys_unexpected) |
| ... keys sent with different payloads | $(value erp_keys_with_different_payloads) |
| Jobs DONE / DEAD | $(value jobs_done) / $(value jobs_dead) |
| Jobs not sent by k6 (must be 0) | $(value jobs_not_sent_by_k6) |
| Orders sent whose key never reached the ERP | $(value erp_keys_missing) |
| Chaos check | $CHAOS_CHECK |
| Requests to the ERP / per accepted order | $(value erp_requests) / $(value erp_requests_per_accepted_order) |
| ... answered 503 / 2xx / other | $(value erp_requests_503) / $(value erp_requests_2xx) / $(value erp_requests_other) |
| Calls refused by the circuit breaker (never sent; app metric) | $(value breaker_cut_calls) |
| Retries scheduled (app metric) | $(value retries_scheduled) |
| Max ERP calls used by one job | $(value max_attempts) |
| Ingest latency p95 / max (k6, webhook -> 202) | $(value ingest_p95_ms) ms / $(value ingest_max_ms) ms |
| End to end p50 / p95 / max (accepted -> DONE) | $(value e2e_p50_ms) ms / $(value e2e_p95_ms) ms / $(value e2e_max_ms) ms |
| k6 requests / failed rate / checks rate / exit code | $(value k6_http_requests) / $(value k6_http_req_failed_rate) / $(value k6_checks_rate) / $(value k6_exit_code) |

Latencies and the ratio are rounded up. Raw data in this folder: \`k6-summary.json\` (k6's own summary),
\`k6-output.log\`, \`sent-orders.txt\`, \`accepted-orders.txt\`, \`results.txt\` (every figure above),
\`environment.txt\` and \`run.log\`.
EOF
log "results in $RESULTS/resilience.md"
cat "$RESULTS/results.txt"
