-- Business key of a sync job: the shop's order id. The HMAC only covers the body, so the same signed order can
-- arrive again under a new X-Shop-Event-Id (a shop redelivering with a fresh delivery id, a replayed request). With
-- this UNIQUE key it still creates one job only. Orders without an id keep NULL, which never conflicts (they go
-- DEAD in the worker anyway).
-- Existing jobs are not backfilled: their payloads would have to be parsed in SQL, and this key only matters for
-- orders that arrive from now on.
ALTER TABLE sync_job ADD COLUMN shop_order_id BIGINT;
CREATE UNIQUE INDEX uq_sync_job_shop_order_id ON sync_job (shop_order_id);
