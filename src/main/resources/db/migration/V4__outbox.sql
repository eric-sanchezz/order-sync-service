-- Stock updates pushed by the ERP, deduplicated by their event id (same idea as inbox_event for shop orders).
CREATE TABLE erp_inbox_event (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(200) NOT NULL UNIQUE,
    payload     TEXT         NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

-- Last known stock per article. changed_at is the ERP's timestamp: an update older than it is stale.
CREATE TABLE stock_level (
    article_code VARCHAR(100) PRIMARY KEY,
    quantity     BIGINT       NOT NULL CHECK (quantity >= 0),
    warehouse    VARCHAR(50),
    changed_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

-- Transactional outbox: written in the same transaction as the state change, delivered later by the relay.
CREATE TABLE outbox_message (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(30)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    destination     VARCHAR(30)  NOT NULL CHECK (destination IN ('SHOP_STOCK_API')),
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RETRY', 'DELIVERED', 'DEAD', 'SUPERSEDED')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    delivered_at    TIMESTAMPTZ
);

-- The relay claims by (status, next_attempt_at); the head-of-line check looks up older messages of the same key.
CREATE INDEX idx_outbox_status_next_attempt ON outbox_message (status, next_attempt_at);
CREATE INDEX idx_outbox_key ON outbox_message (aggregate_id, destination, id);
