-- Event invariant I6: every transfer has exactly one event, and every event is
-- published in the end.
--
-- Every query lists violations, so a sound system returns no rows from any of them.
-- Run by hand once the outbox has drained, after a load test or a chaos scenario:
--
--   psql "postgresql://ledgerly:ledgerly@localhost:5433/ledger" -f scripts/invariants-events.sql
--
-- These are not part of scripts/invariants.sql, which the integration tests run against a database where some
-- tests write ledger transactions directly, without going through the wallet API and so without an event.

-- I6a: every transfer has exactly one TransferCompleted event
SELECT t.id AS transaction_id, count(o.id) AS events
FROM ledger_transactions t
LEFT JOIN outbox_events o ON o.aggregate_id = t.id AND o.event_type = 'TransferCompleted'
WHERE t.type = 'TRANSFER'
GROUP BY t.id
HAVING count(o.id) <> 1;

-- I6b: no event reports a ledger transaction that does not exist
SELECT o.id AS event_id, o.aggregate_id
FROM outbox_events o
LEFT JOIN ledger_transactions t ON t.id = o.aggregate_id
WHERE o.aggregate_type = 'LedgerTransaction' AND t.id IS NULL;

-- I6c: no event is still unpublished a minute after it was written
SELECT id AS event_id, created_at, attempts
FROM outbox_events
WHERE published_at IS NULL AND created_at < now() - interval '1 minute';
