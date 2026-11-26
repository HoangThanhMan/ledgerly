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

# One instant query whose result has one series per value of a label: prints "value=number" pairs.
prom_by() {
  curl -s "$GRAFANA/api/datasources/proxy/uid/prometheus/api/v1/query" --data-urlencode "query=$2" \
    | python3 -I -c '
import json, sys
label = sys.argv[1]
series = json.load(sys.stdin)["data"]["result"]
print(" ".join("{}={:g}".format(s["metric"].get(label, "?"), float(s["value"][1])) for s in series) or "none")
' "$1"
}
