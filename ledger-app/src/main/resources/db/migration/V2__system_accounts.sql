-- System accounts: the counterpart of every money flow into and out of the system,
-- so the balances of all accounts always sum to zero.
INSERT INTO accounts (type, code, currency, allow_negative) VALUES
    -- Source of internal deposits (POST /v1/admin/deposits): goes further negative with each deposit.
    ('SYSTEM', 'system:funding',             'VND', TRUE),
    -- User money held at the bank: top-ups through the bank make this account negative.
    ('SYSTEM', 'system:bank-settlement',     'VND', TRUE),
    -- Withdrawals held until the bank pays out: money only passes through, so it is never negative.
    ('SYSTEM', 'system:withdrawal-suspense', 'VND', FALSE);
