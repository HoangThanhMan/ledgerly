-- Users of the API. A user signs in with email and password and receives a JWT.
CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    email         TEXT        NOT NULL UNIQUE CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254),
    password_hash TEXT        NOT NULL,                    -- BCrypt
    role          TEXT        NOT NULL CHECK (role IN ('USER', 'ORGANIZER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The user a wallet belongs to. NULL for system accounts, and for wallets opened before users existed.
ALTER TABLE accounts ADD COLUMN owner_id UUID REFERENCES users (id);
CREATE INDEX accounts_owner_id ON accounts (owner_id) WHERE owner_id IS NOT NULL;
