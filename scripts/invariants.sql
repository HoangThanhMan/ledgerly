-- Ledger invariants I1–I4 (docs/04-chien-luoc-kiem-thu.md §2).
--
-- Every query lists violations, so a sound ledger returns no rows from any of them.
-- Run by hand after a load test or a chaos scenario:
--
--   psql "postgresql://ledgerly:ledgerly@localhost:5433/ledger" -f scripts/invariants.sql
--
-- The integration tests run these same statements through InvariantChecker, which splits this file on the
-- "-- I<n>:" comment lines. Keep one statement per invariant.

-- I1: every ledger transaction is balanced
SELECT transaction_id, sum(amount) AS entries_sum
FROM entries
GROUP BY transaction_id
HAVING sum(amount) <> 0;

-- I2: no account is negative unless it is allowed to be
SELECT id, balance
FROM accounts
WHERE balance < 0 AND NOT allow_negative;

-- I3: each balance equals the sum of the account's entries
SELECT a.id, a.balance, coalesce(sum(e.amount), 0) AS entries_sum
FROM accounts a
LEFT JOIN entries e ON e.account_id = a.id
GROUP BY a.id
HAVING a.balance <> coalesce(sum(e.amount), 0);

-- I4: all balances, system accounts included, sum to zero
SELECT sum(balance) AS total_balance
FROM accounts
HAVING sum(balance) <> 0;
