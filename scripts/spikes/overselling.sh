#!/usr/bin/env bash
# Spike: four ways to take tickets from one shared counter while many clients buy at once, measured with pgbench.
#
# Needs the compose PostgreSQL running:  docker compose up -d postgres
# Run:                                   scripts/spikes/overselling.sh [clients] [seconds] [runs]   (default 50 20 3)
#
# The script creates a temporary `spike_oversell` database with one ticket class of 1,000,000 tickets, so it never
# sells out during a run. Every strategy runs RUNS times for SECONDS with CLIENTS concurrent clients, each buying one
# ticket per transaction. Per run it prints:
#   tps        transactions per second, as pgbench reports it
#   p50, p99   latency of one transaction in milliseconds, from the pgbench per-transaction log
#   sold       tickets the counter says are gone (1,000,000 - remaining)
#   sales      rows written to the sales table: one per ticket a client believes it bought
#   failed     attempts that bought nothing (attempts - sales)
# A strategy is correct when sold = sales. Then it drops the database.
#
# Strategies:
#   naive               read remaining, then write remaining - 1: no lock, so concurrent writes are lost
#   select-for-update   lock the row with SELECT ... FOR UPDATE, check in the client, then UPDATE
#   conditional-update  UPDATE ... SET remaining = remaining - 1 WHERE remaining >= 1: check and take in one statement
#   optimistic          read remaining and version, then UPDATE ... WHERE version = :version: a client that lost the
#                       race buys nothing and would have to retry (this script does not)
set -euo pipefail
cd "$(dirname "$0")/../.."

CLIENTS=${1:-50}
DURATION=${2:-20}
RUNS=${3:-3}
DB=spike_oversell
TICKETS=1000000

pg() { docker compose exec -T postgres "$@"; }
psql_in() { local db=$1; shift; pg psql -X -q -At -U ledgerly -d "$db" -v ON_ERROR_STOP=1 "$@"; }
script() { pg sh -c "cat > /tmp/oversell-$1.sql"; }

script naive <<'SQL'
BEGIN;
SELECT remaining FROM ticket_classes WHERE id = 1 \gset
UPDATE ticket_classes SET remaining = :remaining - 1 WHERE id = 1;
INSERT INTO sales (qty) VALUES (1);
INSERT INTO attempts DEFAULT VALUES;
COMMIT;
SQL

script select-for-update <<'SQL'
BEGIN;
SELECT remaining FROM ticket_classes WHERE id = 1 FOR UPDATE \gset
\if :remaining >= 1
UPDATE ticket_classes SET remaining = remaining - 1 WHERE id = 1;
INSERT INTO sales (qty) VALUES (1);
\endif
INSERT INTO attempts DEFAULT VALUES;
COMMIT;
SQL

script conditional-update <<'SQL'
BEGIN;
UPDATE ticket_classes SET remaining = remaining - 1 WHERE id = 1 AND remaining >= 1 RETURNING remaining \gset
INSERT INTO sales (qty) VALUES (1);
INSERT INTO attempts DEFAULT VALUES;
COMMIT;
SQL

script optimistic <<'SQL'
SELECT remaining, version FROM ticket_classes WHERE id = 1 \gset
BEGIN;
WITH taken AS (
    UPDATE ticket_classes SET remaining = remaining - 1, version = version + 1
    WHERE id = 1 AND version = :version AND remaining >= 1
    RETURNING 1
)
INSERT INTO sales (qty) SELECT 1 FROM taken;
INSERT INTO attempts DEFAULT VALUES;
COMMIT;
SQL

psql_in ledger -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
trap 'psql_in ledger -c "DROP DATABASE IF EXISTS $DB" >/dev/null' EXIT

psql_in "$DB" <<SQL
CREATE TABLE ticket_classes (
    id        INT    PRIMARY KEY,
    total_qty INT    NOT NULL,
    remaining INT    NOT NULL CHECK (remaining BETWEEN 0 AND total_qty),
    version   BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE sales (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, qty INT NOT NULL);
CREATE TABLE attempts (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
SQL

printf '%-19s %3s %8s %8s %8s %9s %9s %9s\n' strategy run tps p50_ms p99_ms sold sales failed
for strategy in naive select-for-update conditional-update optimistic; do
    for run in $(seq "$RUNS"); do
        psql_in "$DB" -c "TRUNCATE sales, attempts" -c "DELETE FROM ticket_classes" \
            -c "INSERT INTO ticket_classes (id, total_qty, remaining) VALUES (1, $TICKETS, $TICKETS)"
        tps=$(pg sh -c "cd /tmp && rm -f pgbench_log.* && pgbench -n -U ledgerly -c $CLIENTS -j 4 -T $DURATION \
                -f /tmp/oversell-$strategy.sql -l $DB 2>&1" | awk '/^tps/ {printf "%.0f", $3}')
        # Column 3 of the log is the latency of one transaction in microseconds.
        read -r p50 p99 < <(pg sh -c "cat /tmp/pgbench_log.* | awk '{print \$3}' | sort -n | awk '
            { v[NR] = \$1 } END { printf \"%.2f %.2f\\n\", v[int(NR * 0.50)] / 1000, v[int(NR * 0.99)] / 1000 }'")
        sold=$(psql_in "$DB" -c "SELECT total_qty - remaining FROM ticket_classes")
        sales=$(psql_in "$DB" -c "SELECT count(*) FROM sales")
        attempts=$(psql_in "$DB" -c "SELECT count(*) FROM attempts")
        printf '%-19s %3s %8s %8s %8s %9s %9s %9s\n' \
            "$strategy" "$run" "$tps" "$p50" "$p99" "$sold" "$sales" "$((attempts - sales))"
    done
done
