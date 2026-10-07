-- Events this consumer has handled. Kafka delivers an event at least once, and the outbox relay may publish it
-- more than once, so the same eventId can arrive many times. Flow: docs/01-kien-truc.md §6.2.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id         UUID        PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID        NOT NULL,                       -- the wallet whose owner is told
    message    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
