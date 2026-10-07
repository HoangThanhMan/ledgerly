-- One row per Idempotency-Key a client has sent with a money-moving request.
-- State machine and the two-phase flow: docs/01-kien-truc.md §6.1.

CREATE TABLE idempotency_keys (
    idem_key        TEXT        PRIMARY KEY,
    request_hash    TEXT        NOT NULL,                  -- SHA-256 of method + path + normalized body, hex
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- Fencing token: replaced on every claim and reclaim, so only the current holder can complete the key.
    lease_token     UUID        NOT NULL,
    response_status INT,
    -- JSON and not JSONB: a replay returns the stored text unchanged, JSONB would reorder its keys.
    response_body   JSON,
    resource_id     UUID,                                  -- id of what the request created, if anything
    locked_until    TIMESTAMPTZ NOT NULL,                  -- end of the holder's lease: afterwards the key can be reclaimed
    expires_at      TIMESTAMPTZ NOT NULL,                  -- the cleanup job deletes the key after this
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT completed_has_response
        CHECK (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
CREATE INDEX idempotency_keys_expires_at ON idempotency_keys (expires_at);   -- cleanup of expired keys
