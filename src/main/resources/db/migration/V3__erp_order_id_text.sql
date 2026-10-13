-- The ERP order id is an opaque value owned by the ERP. A length limit of our own (it was varchar(100)) turned a
-- successful ERP call into a result we could not store. Postgres stores text and varchar(n) the same way, so the
-- limit protected nothing.
ALTER TABLE sync_job ALTER COLUMN erp_order_id TYPE TEXT;
