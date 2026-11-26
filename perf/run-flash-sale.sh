#!/usr/bin/env bash
# Runs the flash-sale scenario several times. Each run seeds a new event and new buyers, sends one order per buyer,
# and stores what is needed to judge it: the k6 summary, one line per request, server-side metrics, and checks of
# the ticket count and the ledger.
#
#   perf/run-flash-sale.sh <results-name> [runs]
#
# Expects to be started from the repository root with everything running: PostgreSQL, Kafka and the LGTM container
# (compose profile "observability"), ledger-app on :8080 and notification-consumer on :8082, both with
# SPRING_PROFILES_ACTIVE=observability. Start ledger-app with LEDGERLY_SECURITY_PASSWORD_BCRYPT_STRENGTH=4, or
# seeding 20,000 buyers takes many minutes.
#
# Environment: BUYERS (20000), CAPACITY (1000), RATE (2000 buyers per second), MAX_VUS (2000), FIRST_RUN (1),
# BASE_URL (http://localhost:8080), WORK (build/k6-raw), K6_IMAGE (grafana/k6:2.3.0), COMPOSE_PROJECT (ledgerly),
# METRICS_SETTLE (15, seconds to wait after the sale so the applications export their last metrics; the default
# export step is 10 s, and a sale this short is best measured with MANAGEMENT_OTLP_METRICS_EXPORT_STEP=1s).
#
# Start nothing else on the machine while this runs.
set -u

NAME=${1:?usage: perf/run-flash-sale.sh <results-name> [runs]}
RUNS=${2:-3}
BUYERS=${BUYERS:-20000}
CAPACITY=${CAPACITY:-1000}
RATE=${RATE:-2000}
MAX_VUS=${MAX_VUS:-2000}
FIRST_RUN=${FIRST_RUN:-1}
METRICS_SETTLE=${METRICS_SETTLE:-15}
BASE_URL=${BASE_URL:-http://localhost:8080}
WORK=${WORK:-build/k6-raw}
K6_IMAGE=${K6_IMAGE:-grafana/k6:2.3.0}
OUT=perf/results/$NAME

mkdir -p "$OUT" "$WORK"
WORK=$(cd "$WORK" && pwd)

# shellcheck source=perf/lib.sh
. perf/lib.sh

json_field() {
  python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): d=d.get(k, {}) if isinstance(d, dict) else {}
print(d if d != {} else 0)' "$1" "$2"
}

for run in $(seq "$FIRST_RUN" "$((FIRST_RUN + RUNS - 1))"); do
  R=$OUT/run-$run
  RUN_ID="$(date +%s)-$run"
  mkdir -p "$R"
  rm -f "$WORK/buyers.json" "$WORK/raw-$run.json.gz"
  echo "$(date +%T) run $run: seeding $BUYERS buyers for $CAPACITY tickets"
  if ! python3 -I perf/flash-sale-seed.py --base-url "$BASE_URL" --run-id "$RUN_ID" --buyers "$BUYERS" \
      --capacity "$CAPACITY" --out "$WORK/buyers.json" > "$WORK/seed.txt" 2>&1; then
    echo "seeding failed, see $WORK/seed.txt" >&2
    exit 1
  fi
  CLASS=$(json_field "$WORK/buyers.json" ticketClassId)

  notifications_before=$(psql_count notification "select count(*) from notifications")
  events_before=$(psql_count ledger "select count(*) from outbox_events")
  memory_before=$(stall_us memory)
  cpu_before=$(stall_us cpu)
  io_before=$(stall_us io)
  swap_before=$(swap_used_mb)
  echo "$(date +%T) run $run: sale at $RATE buyers per second"
  sale_start=$(date +%s)
  docker run --rm --network host --user "$(id -u):$(id -g)" -v "$PWD/perf:/perf:ro" -v "$WORK:/out" \
    -e BASE_URL="$BASE_URL" -e RATE="$RATE" -e MAX_VUS="$MAX_VUS" -e RUN_ID="$RUN_ID" \
    -e BUYERS_FILE=/out/buyers.json \
    "$K6_IMAGE" run --no-usage-report --quiet --summary-export "/out/summary-$run.json" \
    --out "json=/out/raw-$run.json.gz" /perf/k6/flash-sale.js > "$R/k6-summary.txt" 2>&1
  k6_exit=$?
  memory_stall=$(( ($(stall_us memory) - memory_before) / 1000 ))
  cpu_stall=$(( ($(stall_us cpu) - cpu_before) / 1000 ))
  io_stall=$(( ($(stall_us io) - io_before) / 1000 ))
  if [ ! -f "$WORK/summary-$run.json" ]; then
    echo "k6 wrote no summary (exit code $k6_exit), see $R/k6-summary.txt" >&2
    exit 1
  fi
  cp "$WORK/summary-$run.json" "$R/summary.json"
  sleep "$METRICS_SETTLE"
  # From the start of this sale until now: never reaches back into the seeding or into the previous run.
  WINDOW=$(window_since "$sale_start")

  flash_sale_metrics "$WINDOW" > "$R/server-metrics.json"

  # Count only once the relay has drained and the consumer has caught up with this run's events, for at most two
  # minutes each.
  pending=unknown
  for _ in $(seq 1 60); do
    pending=$(actuator_metric localhost:8080 ledgerly.outbox.pending)
    [ "$pending" = "0.0" ] && break
    sleep 2
  done
  consumer_wait=0
  while [ "$consumer_wait" -lt 120 ] \
    && [ $(( $(psql_count notification "select count(*) from notifications") - notifications_before )) \
         != $(( $(psql_count ledger "select count(*) from outbox_events") - events_before )) ]; do
    sleep 2
    consumer_wait=$((consumer_wait + 2))
  done
  sleep 5

  python3 -I perf/compact-raw.py "$WORK/raw-$run.json.gz" "$R/requests.csv.gz" > /dev/null
  outcomes=$(python3 -I perf/flash-sale-summary.py "$R/requests.csv.gz")
  statuses=$(zcat "$R/requests.csv.gz" | awk -F, '$3=="order" {n[$4]++} END {for (s in n) printf "%s=%d ", s, n[s]}')
  dropped=$(json_field "$R/summary.json" metrics.dropped_iterations.count)
  other=$(json_field "$R/summary.json" metrics.orders_other.count)
  k6_won=$(json_field "$R/summary.json" metrics.orders_won.count)
  k6_sold_out=$(json_field "$R/summary.json" metrics.orders_sold_out.count)
  k6_shed=$(json_field "$R/summary.json" metrics.orders_shed.count)
  conflicts=$(echo "$outcomes" | sed -n 's/.*conflict409=\([0-9]*\).*/\1/p')
  read -r total remaining orders sold <<< "$(psql_count ledger "select c.total_qty, c.remaining, count(o.id), coalesce(sum(o.quantity), 0) from ticket_classes c left join ticket_orders o on o.ticket_class_id = c.id where c.id = '$CLASS' group by c.id" | tr '|' ' ')"
  ledger_violations=$(violations scripts/invariants.sql)
  event_violations=$(violations scripts/invariants-events.sql)

  reasons=""
  [ "$ledger_violations" != "0" ] && reasons="$reasons invariants.sql"
  [ "$event_violations" != "0" ] && reasons="$reasons invariants-events.sql"
  [ "$other" != "0" ] && reasons="$reasons unexpected-statuses"
  # k6 tells a sold-out 409 from any other 409 by its problem type; the per-request file only has the status.
  [ "$conflicts" != "$k6_sold_out" ] && reasons="$reasons outcome-mismatch"
  [ "$((dropped * 100))" -gt "$BUYERS" ] && reasons="$reasons dropped-over-1%"
  [ "$((total - remaining))" != "$sold" ] && reasons="$reasons ticket-count"

  {
    echo "k6 exit code: $k6_exit"
    echo "buyers: $BUYERS, capacity: $CAPACITY, rate: $RATE/s, max VUs: $MAX_VUS, ticket class: $CLASS"
    echo "dropped iterations: $dropped"
    echo "orders, from k6: won=$k6_won sold_out=$k6_sold_out shed=$k6_shed other=$other"
    echo "orders, per request: $outcomes"
    echo "statuses: $statuses"
    echo "ticket class: total $total, remaining $remaining, orders $orders, tickets in orders $sold, total - remaining = tickets in orders: $([ "$((total - remaining))" = "$sold" ] && echo true || echo false)"
    echo "outbox pending after the run: $pending"
    echo "new notifications: $(( $(psql_count notification "select count(*) from notifications") - notifications_before )), new outbox events: $(( $(psql_count ledger "select count(*) from outbox_events") - events_before )) (waited $consumer_wait s for the consumer)"
    echo "invariants.sql violating rows: $ledger_violations"
    echo "invariants-events.sql violating rows: $event_violations"
    echo "memory stall during k6: $memory_stall ms"
    echo "cpu stall during k6: $cpu_stall ms"
    echo "io stall during k6: $io_stall ms"
    echo "swap used before and after: $swap_before MB, $(swap_used_mb) MB"
    if [ -z "$reasons" ]; then echo "usable: yes"; else echo "usable: no ($reasons )"; fi
  } > "$R/checks.txt"

  rm -f "$WORK/raw-$run.json.gz" "$WORK/summary-$run.json" "$WORK/buyers.json"
  echo "$(date +%T) run $run done: $(tail -1 "$R/checks.txt")"
done
