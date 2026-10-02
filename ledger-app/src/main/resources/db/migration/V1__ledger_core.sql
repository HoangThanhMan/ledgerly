-- Double-entry ledger core.
-- Money and sign conventions: ADR-0003. Defensive constraints: docs/01-kien-truc.md §5.3.

CREATE TABLE accounts (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),
    type           TEXT        NOT NULL CHECK (type IN ('USER_WALLET', 'SYSTEM')),
    code           TEXT        UNIQUE,                     -- required for SYSTEM accounts, e.g. system:funding
    currency       CHAR(3)     NOT NULL DEFAULT 'VND',
    balance        BIGINT      NOT NULL DEFAULT 0,         -- minor units of the currency (ADR-0003)
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    version        BIGINT      NOT NULL DEFAULT 0,         -- for optimistic locking
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT currency_iso_4217 CHECK (currency ~ '^[A-Z]{3}$'),
    -- Last line of defense against negative balances if the Java code has a bug
    CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0),
    CONSTRAINT allow_negative_only_for_system CHECK (type = 'SYSTEM' OR NOT allow_negative)
);

CREATE TABLE ledger_transactions (
    id         UUID        PRIMARY KEY DEFAULT uuidv7(),
    type       TEXT        NOT NULL,                       -- TRANSFER, DEPOSIT, TOPUP, WITHDRAWAL_*, REVERSAL
    reference  TEXT,                                       -- id of the related business object
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entries (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    amount         BIGINT      NOT NULL,                   -- signed, from the account's side: negative is out, positive is in
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entries_amount_non_zero CHECK (amount <> 0)
);
CREATE INDEX entries_account_id_id ON entries (account_id, id);    -- entry history, keyset pagination
CREATE INDEX entries_transaction_id ON entries (transaction_id);   -- balance check per transaction

-- The ledger is append-only. Mistakes are fixed with reversing transactions, never UPDATE, DELETE or TRUNCATE.
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only, % is not allowed', TG_TABLE_NAME, TG_OP;
END $$;

CREATE TRIGGER entries_append_only BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER entries_no_truncate BEFORE TRUNCATE ON entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_transactions_append_only BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_transactions_no_truncate BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- The entries of a transaction must sum to zero. Checked at COMMIT (DEFERRABLE INITIALLY DEFERRED),
-- because between two INSERTs of the same transaction the sum is temporarily non-zero.
CREATE FUNCTION assert_transaction_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT sum(amount) FROM entries WHERE transaction_id = NEW.transaction_id) <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced', NEW.transaction_id;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER entries_balanced AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- A transaction without entries is trivially "balanced" but is garbage data. Rejected at COMMIT.
CREATE FUNCTION assert_transaction_has_entries() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM entries WHERE transaction_id = NEW.id) THEN
        RAISE EXCEPTION 'ledger transaction % has no entries', NEW.id;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ledger_transactions_have_entries AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_has_entries();
