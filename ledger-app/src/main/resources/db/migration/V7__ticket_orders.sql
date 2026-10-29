-- A purchase of tickets of one class, paid from one of the buyer's wallets by one ledger transaction.
CREATE TABLE ticket_orders (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    ticket_class_id       UUID        NOT NULL REFERENCES ticket_classes (id),
    buyer_id              UUID        NOT NULL REFERENCES users (id),
    wallet_id             UUID        NOT NULL REFERENCES accounts (id),
    quantity              INT         NOT NULL CHECK (quantity BETWEEN 1 AND 4),
    amount                BIGINT      NOT NULL CHECK (amount > 0),              -- minor units (VND)
    ledger_transaction_id UUID        NOT NULL UNIQUE REFERENCES ledger_transactions (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ticket_orders_buyer ON ticket_orders (buyer_id, created_at DESC, id);   -- my orders, newest first
CREATE INDEX ticket_orders_class ON ticket_orders (ticket_class_id);                 -- tickets sold per class
