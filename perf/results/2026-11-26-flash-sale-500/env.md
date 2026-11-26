# 2026-11-26-flash-sale-500: flash sale on the monolith, 500 buyers per second

One ticket class of 1,000 tickets, 20,000 buyers arriving over 40 s, each sending one order for 1 or 2 tickets
(buyer *i* asks for `1 + i % 2`, so demand is 30,000 tickets).

- Date: 2026-11-26, runs 1 to 3 from 16:32 to 16:38.
- Machine: laptop, Intel Core i7-11800H (8 cores, 16 threads), 7 GB RAM, 4 GB swap, NVMe SSD (ext4), Ubuntu 26.04
  LTS, kernel 7.0.0-44-generic, on mains, governor `powersave`.
- Docker: Engine 29.7.2, Compose 5.5.0, no CPU or memory limits on any container.
  - `postgres:18-alpine` (PostgreSQL 18.7), image defaults (`shared_buffers=128MB`, `synchronous_commit=on`,
    `max_connections=100`).
  - `apache/kafka:4.3.1`, one KRaft broker. `grafana/otel-lgtm:0.35.0`.
  - The applications reach PostgreSQL and Kafka through Docker's published ports (`docker-proxy`).
- JDK: Temurin 25.0.5.1 LTS.
  - `ledger-app`: `taskset -c 0,1 java -Xmx512m -jar ...`, outside Docker. G1, virtual threads, HikariCP 10
    connections (default), one instance, relay in process.
  - `notification-consumer`: `java -Xmx256m -jar ...`, not pinned.
- Code: branch `perf/flash-sale-baseline` at `b35a939`; the application code is that of `main` at `bb277cd`.
- Observability: `SPRING_PROFILES_ACTIVE=observability`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1`, and
  `MANAGEMENT_OTLP_METRICS_EXPORT_STEP=1s` (the transfer baseline used 10 s; a 10-second sale needs a finer step).
- Seeding only: `ledger-app` ran with `LEDGERLY_SECURITY_PASSWORD_BCRYPT_STRENGTH=4`, so signing up 20,000 buyers took
  about a minute. It does not touch the order path.
- Data: not reset between runs. Each run adds an organizer, an event and 20,000 buyers with a wallet holding 200,000
  VND (twice the price of one ticket).
- Load generator: k6 2.3.0 (`grafana/k6:2.3.0`, `--network host`), same machine, no CPU limit,
  `constant-arrival-rate` 500 per second for 40 s, 500 VUs pre-allocated, at most 2,000, 30 s request timeout.
- Run with `RATE=500 perf/run-flash-sale.sh 2026-11-26-flash-sale-500 3`.
- Also running: the author's desktop session (Chrome, VS Code). The containers of another project were stopped for
  the runs. Swap in use before the runs: 3.4 to 3.9 GB.

| Run | Usable | Notes |
|:-:|:-:|---|
| 1 | Yes | No arrival dropped |
| 2 | Yes | No arrival dropped |
| 3 | Yes | No arrival dropped |

The same sale as `2026-11-26-flash-sale` at a quarter of the rate, so that every buyer is sent on schedule and the
latencies describe the whole sale. The ledger and the ticket count stayed correct in every run (1,000 tickets sold,
only 201 and 409 answers, invariants clean).

The runs were made with a runner that queried Prometheus over a fixed two-minute window, which reached back into the
previous run. `server-metrics.json` was recomputed afterwards from the same Prometheus data, over 60 s from the
start of each sale (`flash_sale_metrics` in `perf/lib.sh`, evaluated at that time). The two `orders` lines of
`checks.txt` were rewritten from `summary.json` and `requests.csv.gz` with the current
`perf/flash-sale-summary.py`; every 409 was a sold-out answer. Nothing else was changed.
