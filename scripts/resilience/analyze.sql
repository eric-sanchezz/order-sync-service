-- Results of one resilience run, computed from data only: what k6 saw (orders sent, 202s received), what the
-- database holds (sync jobs) and what the ERP received (WireMock's request journal). Run by
-- scripts/resilience-run.sh with
--   psql -v order_base=<first shop order id> -v order_max=<last possible shop order id>
--        -v chaos_on_ms=<epoch ms the 503 stub went in> -v chaos_off_ms=<epoch ms it was deleted> (0 and 0: no chaos)
-- after copying sent.txt, accepted.txt, journal.json and k6-summary.json (each JSON on one line) to /tmp/resilience/.
-- Prints one key=value line per figure. Nothing is rounded down: latencies and ratios are rounded UP.
--
-- Definitions (per shop order, from the ERP's point of view):
--   lost       = accepted by the webhook (202) and the ERP never answered a 2xx to its Idempotency-Key, whatever
--                the state of its job (DONE included), or it has no job at all.
--   duplicated = more than one job for the shop order id, or more than one 2xx from the ERP for the same key, or a
--                key in the ERP that matches no order k6 sent, or the same key sent with different payloads.
-- An order that never reached the ERP is lost, never duplicated.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

-- New orders k6 posted, and those it got a 202 for.
CREATE TEMP TABLE sent (shop_order_id bigint PRIMARY KEY);
\copy sent FROM '/tmp/resilience/sent.txt'
CREATE TEMP TABLE accepted (shop_order_id bigint PRIMARY KEY);
\copy accepted FROM '/tmp/resilience/accepted.txt'

-- Every request the ERP mock received during the run (its journal was reset at the start).
CREATE TEMP TABLE journal_raw (doc text);
\copy journal_raw FROM '/tmp/resilience/journal.json' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')
CREATE TEMP TABLE erp_request AS
SELECT r -> 'request' -> 'headers' ->> 'Idempotency-Key' AS idempotency_key,
       r -> 'request' ->> 'body' AS body,
       (r -> 'response' ->> 'status')::int AS status,
       (r -> 'request' ->> 'loggedDate')::bigint AS logged_ms
FROM journal_raw, jsonb_array_elements(doc::jsonb -> 'requests') AS r
WHERE r -> 'request' ->> 'url' = '/api/v1/sales-orders';

CREATE TEMP TABLE k6_summary_raw (doc text);
\copy k6_summary_raw FROM '/tmp/resilience/k6-summary.json' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')
CREATE TEMP VIEW k6 AS SELECT doc::jsonb -> 'metrics' AS m FROM k6_summary_raw;

-- The run's sync jobs (its shop order ids are unique to this run).
CREATE TEMP VIEW run_job AS
SELECT * FROM sync_job WHERE shop_order_id BETWEEN :order_base AND :order_max;

-- k6 side
SELECT 'k6_http_requests=' || (m -> 'http_reqs' -> 'values' ->> 'count') FROM k6;
SELECT 'k6_accepted_counter=' || coalesce(m -> 'accepted' -> 'values' ->> 'count', '0') FROM k6;
SELECT 'k6_redelivered=' || coalesce(m -> 'redelivered' -> 'values' ->> 'count', '0') FROM k6;
SELECT 'k6_http_req_failed_rate=' || (m -> 'http_req_failed' -> 'values' ->> 'rate') FROM k6;
SELECT 'k6_checks_rate=' || (m -> 'checks' -> 'values' ->> 'rate') FROM k6;
SELECT 'ingest_p95_ms=' || trim_scale(ceil((m -> 'http_req_duration' -> 'values' ->> 'p(95)')::numeric * 10) / 10) FROM k6;
SELECT 'ingest_max_ms=' || trim_scale(ceil((m -> 'http_req_duration' -> 'values' ->> 'max')::numeric * 10) / 10) FROM k6;
SELECT 'sent_orders=' || count(*) FROM sent;
SELECT 'accepted_orders=' || count(*) FROM accepted;
SELECT 'sent_not_accepted=' || count(*) FROM sent s WHERE NOT EXISTS (SELECT 1 FROM accepted a WHERE a.shop_order_id = s.shop_order_id);
SELECT 'accepted_not_sent=' || count(*) FROM accepted a WHERE NOT EXISTS (SELECT 1 FROM sent s WHERE s.shop_order_id = a.shop_order_id);

-- Database side. (The script only gets here once PENDING + RETRY + IN_PROGRESS = 0.)
SELECT 'queue_not_empty=' || count(*) FROM sync_job WHERE status IN ('PENDING', 'RETRY', 'IN_PROGRESS');
SELECT 'jobs_done=' || count(*) FROM run_job WHERE status = 'DONE';
SELECT 'jobs_dead=' || count(*) FROM run_job WHERE status = 'DEAD';
-- A job for an order k6 never sent means the inputs do not match the run: the script then rejects the run.
SELECT 'jobs_not_sent_by_k6=' || count(*)
FROM run_job j WHERE NOT EXISTS (SELECT 1 FROM sent s WHERE s.shop_order_id = j.shop_order_id);
SELECT 'max_attempts=' || coalesce(max(attempts), 0) FROM run_job;

-- ERP side.
SELECT 'erp_requests=' || count(*) FROM erp_request;
SELECT 'erp_requests_2xx=' || count(*) FILTER (WHERE status BETWEEN 200 AND 299) FROM erp_request;
SELECT 'erp_requests_503=' || count(*) FILTER (WHERE status = 503) FROM erp_request;
SELECT 'erp_requests_other=' || count(*) FILTER (WHERE status NOT BETWEEN 200 AND 299 AND status <> 503) FROM erp_request;
-- The chaos had an effect only if the ERP answered 503 while the chaos stub was in (2 s of margin for the clocks).
SELECT 'erp_requests_503_in_chaos_window=' || count(*) FILTER (WHERE status = 503
       AND logged_ms BETWEEN :chaos_on_ms - 2000 AND :chaos_off_ms + 2000) FROM erp_request;
SELECT 'erp_requests_503_outside_chaos_window=' || count(*) FILTER (WHERE status = 503
       AND NOT coalesce(logged_ms BETWEEN :chaos_on_ms - 2000 AND :chaos_off_ms + 2000, false)) FROM erp_request;
-- Guard: without a key or a body the checks below would pass without checking anything.
SELECT 'erp_requests_without_key_or_body=' || count(*) FROM erp_request
WHERE idempotency_key IS NULL OR body IS NULL OR body = '';
SELECT 'erp_requests_per_accepted_order='
       || trim_scale(ceil(count(*)::numeric / nullif((SELECT count(*) FROM accepted), 0) * 1000) / 1000)
FROM erp_request;
SELECT 'erp_keys=' || count(DISTINCT idempotency_key) FROM erp_request;
-- Informative: orders k6 sent whose key never reached the ERP (if accepted, they are also counted as lost).
SELECT 'erp_keys_missing=' || count(*)
FROM sent s WHERE NOT EXISTS (SELECT 1 FROM erp_request r WHERE r.idempotency_key = 'SHOP-' || s.shop_order_id);

-- Lost, and why.
CREATE TEMP TABLE order_outcome AS
SELECT a.shop_order_id,
       (SELECT count(*) FROM run_job j WHERE j.shop_order_id = a.shop_order_id) AS jobs,
       (SELECT min(j.status) FROM run_job j WHERE j.shop_order_id = a.shop_order_id) AS status,
       (SELECT count(*) FROM erp_request r
        WHERE r.idempotency_key = 'SHOP-' || a.shop_order_id AND r.status BETWEEN 200 AND 299) AS erp_2xx
FROM accepted a;
SELECT 'lost=' || count(*) FROM order_outcome WHERE erp_2xx = 0 OR jobs = 0;
SELECT 'lost_without_job=' || count(*) FROM order_outcome WHERE jobs = 0;
SELECT 'lost_dead=' || count(*) FROM order_outcome WHERE jobs > 0 AND erp_2xx = 0 AND status = 'DEAD';
SELECT 'lost_done_without_erp_2xx=' || count(*) FROM order_outcome WHERE jobs > 0 AND erp_2xx = 0 AND status = 'DONE';

-- Duplicated, and why. Each condition is counted on its own; "duplicated" counts the distinct keys behind them.
CREATE TEMP TABLE duplicate_key AS
SELECT 'SHOP-' || shop_order_id AS idempotency_key, 'more_than_one_job' AS reason
FROM run_job GROUP BY shop_order_id HAVING count(*) > 1
UNION ALL
SELECT idempotency_key, 'more_than_one_2xx' FROM erp_request WHERE status BETWEEN 200 AND 299
GROUP BY idempotency_key HAVING count(*) > 1
UNION ALL
SELECT DISTINCT idempotency_key, 'unexpected_key' FROM erp_request r
WHERE NOT EXISTS (SELECT 1 FROM sent s WHERE r.idempotency_key = 'SHOP-' || s.shop_order_id)
UNION ALL
SELECT idempotency_key, 'different_payloads' FROM erp_request GROUP BY idempotency_key HAVING count(DISTINCT body) > 1;
SELECT 'duplicated=' || count(DISTINCT idempotency_key) FROM duplicate_key;
SELECT 'orders_with_more_than_one_job=' || count(*) FILTER (WHERE reason = 'more_than_one_job') FROM duplicate_key;
SELECT 'erp_keys_with_more_than_one_2xx=' || count(*) FILTER (WHERE reason = 'more_than_one_2xx') FROM duplicate_key;
SELECT 'erp_keys_unexpected=' || count(*) FILTER (WHERE reason = 'unexpected_key') FROM duplicate_key;
SELECT 'erp_keys_with_different_payloads=' || count(*) FILTER (WHERE reason = 'different_payloads') FROM duplicate_key;

-- WireMock journal: everything it holds was returned, and it never reached its capacity (100000).
SELECT 'journal_total=' || (doc::jsonb -> 'meta' ->> 'total') FROM journal_raw;
SELECT 'journal_returned=' || jsonb_array_length(doc::jsonb -> 'requests') FROM journal_raw;

-- End to end: webhook accepted -> DONE (created_at -> completed_at) for the run's jobs.
CREATE TEMP TABLE e2e AS
SELECT extract(EPOCH FROM (completed_at - created_at)) * 1000 AS ms FROM run_job WHERE status = 'DONE';
SELECT 'e2e_p50_ms=' || coalesce(ceil(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric), 0) FROM e2e;
SELECT 'e2e_p95_ms=' || coalesce(ceil(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric), 0) FROM e2e;
SELECT 'e2e_max_ms=' || coalesce(ceil(max(ms)::numeric), 0) FROM e2e;
