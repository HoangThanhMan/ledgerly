-- Transactional outbox: one row per event, written in the transaction that makes the change the event reports.
-- A relay publishes the rows to Kafka afterwards. Flow: docs/01-kien-truc.md §6.2.

CREATE TABLE outbox_events (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),  -- the eventId consumers deduplicate on
    topic          TEXT        NOT NULL,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   UUID        NOT NULL,                      -- Kafka record key: one aggregate, one partition
    event_type     TEXT        NOT NULL,
    event_version  INT         NOT NULL,
    payload        JSONB       NOT NULL,
    headers        JSONB       NOT NULL DEFAULT '{}',         -- Kafka record headers. Nothing writes them yet.
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                               -- NULL until Kafka has acknowledged the event
    attempts       INT         NOT NULL DEFAULT 0,            -- relay attempts that failed
    -- A change is reported once. Writing the same event twice is a bug in the caller, caught here.
    CONSTRAINT one_event_per_aggregate_and_type UNIQUE (aggregate_id, event_type)
);

-- The relay polls for unpublished events several times a second. Published events, almost the whole table after
-- a while, are not in this index, so the poll stays as cheap as the backlog is small.
CREATE INDEX outbox_events_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;
