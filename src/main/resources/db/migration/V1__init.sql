CREATE TABLE inbox_event (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(200) NOT NULL UNIQUE,
    payload     TEXT         NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE sync_job (
    id              BIGSERIAL PRIMARY KEY,
    inbox_event_id  BIGINT       NOT NULL UNIQUE REFERENCES inbox_event (id),
    event_id        VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RETRY', 'DONE', 'DEAD')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    erp_order_id    VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ
);

-- The worker claims by (status, next_attempt_at).
CREATE INDEX idx_sync_job_status_next_attempt ON sync_job (status, next_attempt_at);
