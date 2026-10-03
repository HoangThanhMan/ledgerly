#!/usr/bin/env bash
# Spike: shows row locks waiting on each other, and a deadlock when two sessions lock in opposite order.
#
# Needs the compose PostgreSQL running:  docker compose up -d postgres
# Run:                                   scripts/spikes/locking.sh [1|2|3|4]   (no argument = all four)
#
# The script creates a temporary `spike` database, runs two psql sessions in parallel on a fixed
# timeline, then drops the database. Lines with ">>" mark when the script sends a statement, the other
# lines are psql output. `Time:` is how long the statement ran, including time spent waiting for locks.
set -euo pipefail
cd "$(dirname "$0")/../.."

psql_in() { local db=$1; shift; docker compose exec -T postgres psql -X -U ledgerly -d "$db" "$@"; }
# HH:MM:SS.mmm. Uses bash EPOCHREALTIME because `date +%3N` does not behave the same across coreutils versions.
now() { local t=${EPOCHREALTIME/,/.}; printf '%(%T)T.%s' "${t%.*}" "${t#*.}" | cut -c1-12; }

# session NAME STEP...: each step is an SQL statement, or "sleep N" to wait before sending the next step.
session() {
    local name=$1; shift
    {
        printf '%s\n' "SET application_name = '$name';" '\timing on'
        for step in "$@"; do
            if [[ $step == sleep\ * ]]; then
                $step
            else
                printf '%s [%s] >> %s\n' "$(now)" "$name" "$step" >&2
                printf '%s\n' "$step"
            fi
        done
    } | psql_in spike 2>&1 | while IFS= read -r line; do printf '%s [%s]    %s\n' "$(now)" "$name" "$line"; done
}

observe() {
    sleep "$1"
    printf '%s [OBS] >> pg_stat_activity\n' "$(now)"
    psql_in spike -q -c "SELECT application_name AS phien, wait_event_type, wait_event,
                                (SELECT array_agg(b.application_name) FROM pg_stat_activity b
                                  WHERE b.pid = ANY (pg_blocking_pids(a.pid))) AS bi_chan_boi,
                                query
                           FROM pg_stat_activity a
                          WHERE datname = 'spike' AND application_name IN ('S1', 'S2')
                          ORDER BY application_name;" | sed "s/^/$(now) [OBS]    /"
}

setup() {
    psql_in ledger -q -c 'DROP DATABASE IF EXISTS spike;' 2>/dev/null
    psql_in ledger -q -c 'CREATE DATABASE spike;'
    psql_in spike -q <<'SQL'
CREATE TABLE accounts (id int PRIMARY KEY, balance bigint NOT NULL);
INSERT INTO accounts VALUES (1, 1000000), (2, 1000000);
SQL
    psql_in spike -At -c 'SHOW deadlock_timeout;' | sed 's/^/deadlock_timeout = /'
}

teardown() { psql_in ledger -q -c 'DROP DATABASE IF EXISTS spike;' 2>/dev/null; }

lock() { echo "SELECT * FROM accounts WHERE id = $1 FOR UPDATE;"; }

scenario_1() {
    echo "== Kịch bản 1: khóa ngược thứ tự (S1: 1 rồi 2, S2: 2 rồi 1)"
    session S1 'BEGIN;' "$(lock 1)" 'sleep 2' "$(lock 2)" 'sleep 2' 'COMMIT;' &
    session S2 'sleep 1' 'BEGIN;' "$(lock 2)" 'sleep 1.7' "$(lock 1)" 'sleep 1.8' 'COMMIT;' &
    observe 2.4 &
    wait
}

scenario_2() {
    echo "== Kịch bản 2: cùng thứ tự id tăng dần (S1 và S2: 1 rồi 2)"
    session S1 'BEGIN;' "$(lock 1)" 'sleep 2' "$(lock 2)" 'sleep 1' 'COMMIT;' &
    session S2 'sleep 1' 'BEGIN;' "$(lock 1)" "$(lock 2)" 'COMMIT;' &
    observe 2 &
    wait
}

scenario_3() {
    echo "== Kịch bản 3: một câu lệnh khóa cả hai dòng, ORDER BY id (như luồng 6.1)"
    psql_in spike -c 'EXPLAIN (COSTS OFF) SELECT * FROM accounts WHERE id IN (2, 1) ORDER BY id FOR UPDATE;'
    session S1 'BEGIN;' 'SELECT * FROM accounts WHERE id IN (1, 2) ORDER BY id FOR UPDATE;' 'sleep 2' 'COMMIT;' &
    session S2 'sleep 1' 'BEGIN;' 'SELECT * FROM accounts WHERE id IN (2, 1) ORDER BY id FOR UPDATE;' 'COMMIT;' &
    wait
}

# move ID AMOUNT: adds AMOUNT (positive) or subtracts it (negative) from the balance of account ID.
move() { if (( $2 < 0 )); then echo "UPDATE accounts SET balance = balance - ${2#-} WHERE id = $1;"; else echo "UPDATE accounts SET balance = balance + $2 WHERE id = $1;"; fi; }

scenario_4() {
    echo "== Kịch bản 4: chuyển tiền bằng UPDATE, ngược chiều nhau (S1: 1→2, S2: 2→1)"
    session S1 'BEGIN;' "$(move 1 -100)" 'sleep 2' "$(move 2 100)" 'sleep 2' 'COMMIT;' &
    session S2 'sleep 1' 'BEGIN;' "$(move 2 -100)" 'sleep 1.7' "$(move 1 100)" 'sleep 1.8' 'COMMIT;' &
    wait
    echo "-- Sau khi cả hai phiên kết thúc:"
    psql_in spike -c 'SELECT id, balance FROM accounts ORDER BY id;' -c 'SELECT sum(balance) AS tong FROM accounts;'
}

trap teardown EXIT
for n in "${@:-1 2 3 4}"; do
    for s in $n; do
        setup
        "scenario_$s"
        echo
    done
done
