-- Events and their ticket classes. An organizer runs an event and is paid into one of their wallets.
CREATE TABLE events (
    id               UUID        PRIMARY KEY DEFAULT uuidv7(),
    organizer_id     UUID        NOT NULL REFERENCES users (id),
    payout_wallet_id UUID        NOT NULL REFERENCES accounts (id),
    name             TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    starts_at        TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX events_starts_at_id ON events (starts_at, id);   -- the event list, ordered by start

-- A class of tickets of one event, with one price. Fixed once created, except remaining, which only sales change.
CREATE TABLE ticket_classes (
    id        UUID   PRIMARY KEY DEFAULT uuidv7(),
    event_id  UUID   NOT NULL REFERENCES events (id),
    name      TEXT   NOT NULL CHECK (length(name) BETWEEN 1 AND 50),
    price     BIGINT NOT NULL CHECK (price BETWEEN 1 AND 1000000000),     -- minor units (VND)
    total_qty INT    NOT NULL CHECK (total_qty BETWEEN 1 AND 100000),
    remaining INT    NOT NULL,
    -- The last line of defence against overselling: no sale can take remaining below zero.
    CONSTRAINT remaining_in_range CHECK (remaining BETWEEN 0 AND total_qty),
    UNIQUE (event_id, name)
);
