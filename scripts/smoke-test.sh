#!/usr/bin/env bash
# End-to-end check of a running system, the way a client sees it: a deposit, a transfer, the retry of that
# transfer, the notification that the consumer writes from the event, and the invariants of the ledger.
#
#   docker compose --profile full up -d --wait
#   scripts/smoke-test.sh
#
# Exits with 0 only if every step passes. Needs curl, jq and docker compose.
# Environment: LEDGER_APP_URL (http://localhost:8080), NOTIFICATION_CONSUMER_URL (http://localhost:8082),
# COMPOSE_PROJECT (ledgerly): the compose project whose postgres service holds the databases.
set -euo pipefail

API=${LEDGER_APP_URL:-http://localhost:8080}
CONSUMER=${NOTIFICATION_CONSUMER_URL:-http://localhost:8082}
COMPOSE_PROJECT=${COMPOSE_PROJECT:-ledgerly}
cd "$(dirname "$0")/.."

step() { printf '%-64s' "$1"; }
ok() { echo "ok${1:+ ($1)}"; }
fail() { echo "FAILED"; echo "  $1" >&2; exit 1; }
expect() { [ "$2" = "$3" ] || fail "$1: expected '$3', got '$2'"; }

psql() { docker compose -p "$COMPOSE_PROJECT" exec -T postgres psql -U ledgerly -d "$1" -At "${@:2}"; }
post() { curl -sS -X POST "$API$1" -H 'Content-Type: application/json' "${@:2}"; }
key() { cat /proc/sys/kernel/random/uuid; }

step "both applications report UP"
expect "ledger-app health" "$(curl -sS "$API/actuator/health" | jq -r .status)" UP
expect "notification-consumer health" "$(curl -sS "$CONSUMER/actuator/health" | jq -r .status)" UP
ok

step "the OpenAPI document is served"
expect "operationId of POST /v1/transfers" \
  "$(curl -sS "$API/v3/api-docs" | jq -r '.paths["/v1/transfers"].post.operationId')" createTransfer
ok

step "open two wallets and deposit 500000 into the first"
A=$(post /v1/wallets -d '{"currency":"VND"}' | jq -r .id)
B=$(post /v1/wallets -d '{"currency":"VND"}' | jq -r .id)
deposit=$(post /v1/admin/deposits -H "Idempotency-Key: $(key)" \
  -d "{\"walletId\":\"$A\",\"amount\":\"500000\",\"currency\":\"VND\"}")
expect "deposit status" "$(jq -r .status <<<"$deposit")" COMPLETED
ok

step "transfer 150000 from the first wallet to the second"
KEY=$(key)
BODY="{\"sourceWalletId\":\"$A\",\"targetWalletId\":\"$B\",\"amount\":\"150000\",\"currency\":\"VND\"}"
first=$(post /v1/transfers -H "Idempotency-Key: $KEY" -d "$BODY")
expect "transfer status" "$(jq -r .status <<<"$first")" COMPLETED
ok

step "the same request again replays the response and moves nothing"
headers=$(mktemp)
second=$(post /v1/transfers -H "Idempotency-Key: $KEY" -d "$BODY" -D "$headers")
grep -qi '^Idempotent-Replayed: true' "$headers" || fail "the retry has no Idempotent-Replayed: true header"
rm -f "$headers"
expect "replayed body" "$second" "$first"
expect "balance of the first wallet" "$(curl -sS "$API/v1/wallets/$A" | jq -r .balance)" 350000
expect "balance of the second wallet" "$(curl -sS "$API/v1/wallets/$B" | jq -r .balance)" 150000
ok

step "a transfer the wallet cannot afford is a 422 problem"
problem=$(post /v1/transfers -H "Idempotency-Key: $(key)" \
  -d "{\"sourceWalletId\":\"$B\",\"targetWalletId\":\"$A\",\"amount\":\"150001\",\"currency\":\"VND\"}")
expect "problem type" "$(jq -r .type <<<"$problem")" /problems/insufficient-funds
ok

step "the consumer writes one notification for the second wallet"
# Usually within a second. After a consumer was killed, the broker first waits for its session to time out
# (45 s by default) before the partitions go to the running consumer.
waited=0
until [ "$(psql notification -c "SELECT count(*) FROM notifications WHERE account_id = '$B'")" = 1 ]; do
  [ "$waited" -lt 90 ] || fail "no notification for wallet $B after 90 s"
  sleep 1
  waited=$((waited + 1))
done
ok "after $waited s"

step "ledger and event invariants hold"
for script in scripts/invariants.sql scripts/invariants-events.sql; do
  violations=$(psql ledger <"$script")
  [ -z "$violations" ] || fail "$script lists violations: $violations"
done
ok

echo "Smoke test passed."
