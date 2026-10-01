-- Lõi sổ cái kép (W02-05).
-- Quy ước tiền và dấu: ADR-0003. Ràng buộc phòng thủ: docs/01-kien-truc.md §5.3.

CREATE TABLE accounts (
    id             UUID        PRIMARY KEY DEFAULT uuidv7(),
    type           TEXT        NOT NULL CHECK (type IN ('USER_WALLET', 'SYSTEM')),
    code           TEXT        UNIQUE,                     -- bắt buộc với account SYSTEM, ví dụ system:funding
    currency       CHAR(3)     NOT NULL DEFAULT 'VND',
    balance        BIGINT      NOT NULL DEFAULT 0,         -- đơn vị nhỏ nhất của tiền tệ (ADR-0003)
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    version        BIGINT      NOT NULL DEFAULT 0,         -- cho thí nghiệm khóa lạc quan ở tuần 11
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT currency_iso_4217 CHECK (currency ~ '^[A-Z]{3}$'),
    -- I2: lớp chặn cuối nếu code Java có bug
    CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0),
    CONSTRAINT allow_negative_only_for_system CHECK (type = 'SYSTEM' OR NOT allow_negative)
);

CREATE TABLE ledger_transactions (
    id         UUID        PRIMARY KEY DEFAULT uuidv7(),
    type       TEXT        NOT NULL,                       -- TRANSFER, DEPOSIT, TOPUP, WITHDRAWAL_*, REVERSAL
    reference  TEXT,                                       -- id nghiệp vụ liên quan
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entries (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    amount         BIGINT      NOT NULL,                   -- có dấu, nhìn từ phía account: âm là ra, dương là vào
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entries_amount_non_zero CHECK (amount <> 0)
);
CREATE INDEX entries_account_id_id ON entries (account_id, id);    -- lịch sử bút toán, phân trang keyset
CREATE INDEX entries_transaction_id ON entries (transaction_id);   -- kiểm tra I1

-- P2: sổ cái chỉ được thêm. Sửa sai bằng giao dịch đảo, không UPDATE, DELETE hay TRUNCATE.
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

-- I1: tổng entry của một giao dịch bằng 0. Kiểm tra lúc COMMIT (DEFERRABLE INITIALLY DEFERRED),
-- vì giữa hai câu INSERT của cùng một giao dịch thì tổng tạm thời khác 0.
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

-- Một giao dịch không có entry nào thì cũng "cân bằng", nhưng là dữ liệu rác. Chặn lúc COMMIT.
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
