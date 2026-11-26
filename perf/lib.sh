# Helpers shared by the load-test runners (perf/run-baseline.sh, perf/run-flash-sale.sh). Source it from the
# repository root. Uses COMPOSE_PROJECT (default ledgerly) and GRAFANA (the LGTM container's Grafana).
COMPOSE_PROJECT=${COMPOSE_PROJECT:-ledgerly}
GRAFANA=${GRAFANA:-http://admin:admin@localhost:3000}

psql_count() {
  docker compose -p "$COMPOSE_PROJECT" exec -T postgres psql -U ledgerly -d "$1" -Atc "$2"
}

violations() {
  docker compose -p "$COMPOSE_PROJECT" exec -T postgres psql -U ledgerly -d ledger -At < "$1" | grep -c .
}

# One instant query against the Prometheus inside the LGTM container.
# Evaluated now, or at the Unix time in PROM_TIME if it is set.
prom() {
  curl -s "$GRAFANA/api/datasources/proxy/uid/prometheus/api/v1/query" --data-urlencode "query=$1" \
    ${PROM_TIME:+--data-urlencode "time=$PROM_TIME"} \
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


# A Prometheus range from the Unix time $1 until now, such as "37s": a window that holds one run and nothing before it.
window_since() {
  echo "$(( $(date +%s) - $1 ))s"
}

# Server-side metrics of one flash sale, as JSON, over the Prometheus range $1 (see window_since).
flash_sale_metrics() {
  local app='service_name="ledger-app"'
  echo "{"
  echo "  \"ledger_cpu_avg\": $(prom "avg_over_time(process_cpu_usage{$app}[$1])"),"
  echo "  \"ledger_cpu_max\": $(prom "max_over_time(process_cpu_usage{$app}[$1])"),"
  echo "  \"server_p99_ms\": $(prom "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{$app,uri=\"/v1/orders\",method=\"POST\"}[$1])))"),"
  echo "  \"lock_wait_p99_ms\": $(prom "histogram_quantile(0.99, sum by (le) (rate(ledgerly_posting_lock_wait_milliseconds_bucket{$app}[$1])))"),"
  echo "  \"hikari_pending_max\": $(prom "max_over_time(hikaricp_connections_pending{$app}[$1])"),"
  echo "  \"hikari_active_max\": $(prom "max_over_time(hikaricp_connections_active{$app}[$1])"),"
  echo "  \"outbox_pending_max\": $(prom "max_over_time(ledgerly_outbox_pending[$1])"),"
  echo "  \"outbox_oldest_age_max_ms\": $(prom "max_over_time(ledgerly_outbox_oldest_age_milliseconds[$1])")"
  echo "}"
}
