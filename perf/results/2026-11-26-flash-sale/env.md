# 2026-11-26-flash-sale: flash sale on the monolith, 2,000 buyers per second

One ticket class of 1,000 tickets, 20,000 buyers arriving over 10 s, each sending one order for 1 or 2 tickets
(buyer *i* asks for `1 + i % 2`, so demand is 30,000 tickets).

- Date: 2026-11-26, runs 1 to 3 from 16:26 to 16:32.
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
  `constant-arrival-rate` 2,000 per second for 10 s, 2,000 VUs pre-allocated and at most, 30 s request timeout.
- Run with `perf/run-flash-sale.sh 2026-11-26-flash-sale 3`.
- Also running: the author's desktop session (Chrome, VS Code). The containers of another project were stopped for
  the runs. Swap in use before the runs: 2.8 to 3.3 GB.

| Run | Usable | Notes |
|:-:|:-:|---|
| 1 | No (by the runner's rule) | 13,156 of 20,000 arrivals dropped. First sale after the application started (cold JIT) |
| 2 | No (by the runner's rule) | 8,879 dropped |
| 3 | No (by the runner's rule) | 8,375 dropped |

The runner marks a run unusable when more than 1% of arrivals are dropped, because the run then no longer describes
the arrival rate it claims. Here the drops are the finding, not a disturbance: the application put up to 1,990
requests in the queue for its 10 database connections, every one of the 2,000 VUs was waiting for an answer, and k6
could not start more buyers. No run at this rate can be usable on this configuration. The batch
`2026-11-26-flash-sale-500` repeats the sale at 500 buyers per second, where nothing is dropped.

In every run the ledger and the ticket count stayed correct: exactly 1,000 tickets sold, no response other than 201
or 409, every order with one event and one notification, and no row from either invariant script.

The runs were made with a runner that queried Prometheus over a fixed two-minute window, which reached back into the
previous run. `server-metrics.json` was recomputed afterwards from the same Prometheus data, over 30 s from the
start of each sale (`flash_sale_metrics` in `perf/lib.sh`, evaluated at that time). The two `orders` lines of
`checks.txt` were rewritten from `summary.json` and `requests.csv.gz` with the current
`perf/flash-sale-summary.py`; every 409 was a sold-out answer. Nothing else was changed.
