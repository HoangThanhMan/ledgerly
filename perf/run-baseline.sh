#!/usr/bin/env bash
# Runs the transfer scenario several times and stores, for each run, what is needed to judge it:
# the k6 summary, one duration per request, server-side metrics, and the consistency checks.
#
#   perf/run-baseline.sh <results-name> [rate] [runs]
#
# Expects to be started from the repository root with everything already running: PostgreSQL, Kafka and the
# LGTM container (compose profile "observability"), ledger-app on :8080 and notification-consumer on :8082,
# both with SPRING_PROFILES_ACTIVE=observability and the "metrics" actuator endpoint exposed.
#
# Environment: COMPOSE_PROJECT (ledgerly), K6_IMAGE (grafana/k6:2.3.0), WORK (build/k6-raw, holds the raw k6
# output, about 15 MB per run, deleted run by run), WARMUP (1m), DURATION (5m), WALLETS (1000), FIRST_RUN (1,
# set it to add runs to a results directory without overwriting the ones it has).
#
# Start nothing else on the machine while this runs. A run records how long processes were stalled waiting
# for memory, CPU and disk, so that a disturbed run can be recognised afterwards.
set -u

NAME=${1:?usage: perf/run-baseline.sh <results-name> [rate] [runs]}
RATE=${2:-300}
RUNS=${3:-3}
COMPOSE_PROJECT=${COMPOSE_PROJECT:-ledgerly}
K6_IMAGE=${K6_IMAGE:-grafana/k6:2.3.0}
WARMUP=${WARMUP:-1m}
DURATION=${DURATION:-5m}
WALLETS=${WALLETS:-1000}
FIRST_RUN=${FIRST_RUN:-1}
WORK=${WORK:-build/k6-raw}
OUT=perf/results/$NAME
GRAFANA=http://admin:admin@localhost:3000

mkdir -p "$OUT" "$WORK"
# Docker takes a relative path for the name of a volume.
WORK=$(cd "$WORK" && pwd)

psql_count() {
  docker compose -p "$COMPOSE_PROJECT" exec -T postgres psql -U ledgerly -d "$1" -Atc "$2"
}

violations() {
  docker compose -p "$COMPOSE_PROJECT" exec -T postgres psql -U ledgerly -d ledger -At < "$1" | grep -c .
}

# One instant query against the Prometheus inside the LGTM container.
prom() {
  curl -s "$GRAFANA/api/datasources/proxy/uid/prometheus/api/v1/query" --data-urlencode "query=$1" \
    | python3 -I -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "null")'
}

actuator_metric() {
  curl -s "$1/actuator/metrics/$2" \
    | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["measurements"][0]["value"])'
}

# Microseconds since boot during which at least one process was stalled waiting for the resource:
# memory, cpu or io.
stall_us() {
  awk '/^some/ { split($5, total, "="); print total[2] }' "/proc/pressure/$1"
}

swap_used_mb() {
  free -m | awk 'NR==3 { print $3 }'
}

for run in $(seq "$FIRST_RUN" "$((FIRST_RUN + RUNS - 1))"); do
  R=$OUT/run-$run
  mkdir -p "$R"
  rm -f "$WORK/raw-$run.json.gz"
  memory_before=$(stall_us memory)
  cpu_before=$(stall_us cpu)
  io_before=$(stall_us io)
  swap_before=$(swap_used_mb)
  echo "$(date +%T) run $run at $RATE requests per second"

  docker run --rm --network host --user "$(id -u):$(id -g)" -v "$PWD/perf:/perf:ro" -v "$WORK:/out" \
    -e RATE="$RATE" -e WARMUP="$WARMUP" -e DURATION="$DURATION" -e WALLETS="$WALLETS" \
    -e RUN_ID="$(date +%s)-$run" \
    "$K6_IMAGE" run --no-usage-report --quiet --summary-export "/out/summary-$run.json" \
    --out "json=/out/raw-$run.json.gz" /perf/k6/transfer-constant-rate.js > "$R/k6-summary.txt" 2>&1
  k6_exit=$?
  memory_stall=$(( ($(stall_us memory) - memory_before) / 1000 ))
  cpu_stall=$(( ($(stall_us cpu) - cpu_before) / 1000 ))
  io_stall=$(( ($(stall_us io) - io_before) / 1000 ))
  if [ ! -f "$WORK/summary-$run.json" ]; then
    echo "k6 wrote no summary (exit code $k6_exit), see $R/k6-summary.txt" >&2
    exit 1
  fi
  cp "$WORK/summary-$run.json" "$R/summary.json"

  # The windows end now and are as long as the measured phase, so they hold nothing of the warm-up.
  app='service_name="ledger-app"'
  {
    echo "{"
    echo "  \"ledger_cpu_avg\": $(prom "avg_over_time(process_cpu_usage{$app}[$DURATION])"),"
    echo "  \"ledger_cpu_max\": $(prom "max_over_time(process_cpu_usage{$app}[$DURATION])"),"
    echo "  \"server_p99_ms\": $(prom "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{$app,uri=\"/v1/transfers\",method=\"POST\"}[$DURATION])))"),"
    echo "  \"sql_p99_ms\": $(prom "histogram_quantile(0.99, sum by (le) (rate(jdbc_query_milliseconds_bucket{$app}[$DURATION])))"),"
    echo "  \"lock_wait_p99_ms\": $(prom "histogram_quantile(0.99, sum by (le) (rate(ledgerly_posting_lock_wait_milliseconds_bucket{$app}[$DURATION])))"),"
    echo "  \"hikari_pending_max\": $(prom "max_over_time(hikaricp_connections_pending{$app}[$DURATION])"),"
    echo "  \"hikari_active_max\": $(prom "max_over_time(hikaricp_connections_active{$app}[$DURATION])"),"
    echo "  \"outbox_pending_max\": $(prom "max_over_time(ledgerly_outbox_pending[$DURATION])"),"
    echo "  \"outbox_oldest_age_max_ms\": $(prom "max_over_time(ledgerly_outbox_oldest_age_milliseconds[$DURATION])")"
    echo "}"
  } > "$R/server-metrics.json"

  # Wait for the relay to drain and then for the consumer to catch up before counting, for at most two
  # minutes each. After an overloaded run both are behind, and counting early looks like lost events.
  pending=unknown
  for _ in $(seq 1 60); do
    pending=$(actuator_metric localhost:8080 ledgerly.outbox.pending)
    [ "$pending" = "0.0" ] && break
    sleep 2
  done
  consumer_wait=0
  while [ "$consumer_wait" -lt 120 ] \
    && [ "$(psql_count notification "select count(*) from notifications")" != "$(psql_count ledger "select count(*) from outbox_events")" ]; do
    sleep 2
    consumer_wait=$((consumer_wait + 2))
  done
  sleep 5

  {
    echo "k6 exit code: $k6_exit"
    echo "outbox pending after the run: $pending"
    echo "transfers in ledger: $(psql_count ledger "select count(*) from ledger_transactions where type='TRANSFER'")"
    echo "outbox events: $(psql_count ledger "select count(*) from outbox_events") unpublished: $(psql_count ledger "select count(*) from outbox_events where published_at is null")"
    echo "notifications: $(psql_count notification "select count(*) from notifications") (waited $consumer_wait s for the consumer)"
    echo "duplicates counted by consumer: $(actuator_metric localhost:8082 notification.duplicates 2>/dev/null)"
    echo "invariants.sql violating rows: $(violations scripts/invariants.sql)"
    echo "invariants-events.sql violating rows: $(violations scripts/invariants-events.sql)"
    echo "ledger database size: $(psql_count ledger "select pg_size_pretty(pg_database_size('ledger'))")"
    echo "memory stall during k6: $memory_stall ms"
    echo "cpu stall during k6: $cpu_stall ms"
    echo "io stall during k6: $io_stall ms"
    echo "swap used before and after: $swap_before MB, $(swap_used_mb) MB"
  } > "$R/checks.txt"

  python3 -I perf/compact-raw.py "$WORK/raw-$run.json.gz" "$R/requests.csv.gz"
  rm -f "$WORK/raw-$run.json.gz" "$WORK/summary-$run.json"
  echo "$(date +%T) run $run done, k6 exit code $k6_exit"
done
