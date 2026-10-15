-- Last sequence number given to a domain event of each aggregate (an order, a SKU). Incremented with an upsert in the
-- transaction that writes the event, so the row lock serialises the writers of one aggregate: the sequence follows
-- the order in which their transactions commit, which is also the outbox (publication) order (ADR 0006).
CREATE TABLE event_sequence (
    aggregate_type VARCHAR(30)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    last_sequence  BIGINT       NOT NULL,
    PRIMARY KEY (aggregate_type, aggregate_id)
);
