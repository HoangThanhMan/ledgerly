# Changelog

All notable changes to this project are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-19

The first release: wallets, deposits and transfers on a double-entry ledger, safe under concurrency and retries, with events delivered through an outbox to an idempotent consumer.

### Added

**Ledger**

- Core ledger schema with invariants enforced by the database: append-only entries, entries of a transaction that sum to zero, no negative wallet balance
- `Money` in minor units and the posting rules, as plain Java
- JDBC repositories and the ledger service
- Account rows are locked in `id` order before a posting, with a lock timeout that answers `503`

**Wallet API**

- Open a wallet, read its balance and entry history, transfer, deposit, with RFC 9457 Problem Details for errors
- Transfers and deposits are idempotent per `Idempotency-Key`
- An OpenAPI document that describes every operation, parameter and problem, served with Swagger UI and kept as `ledger-app/openapi.yaml`

**Idempotency**

- `idempotency_keys` table and repository
- An action runs at most once per key, in two phases with a lease and a fencing token
- Expired keys are deleted in batches on a schedule

**Events**

- Event envelope, `TransferCompleted` and topic names shared by producer and consumer
- A `TransferCompleted` event is written in the transfer's own transaction
- A relay publishes outbox events to Kafka at least once
- `notification-consumer` handles each event id once

**Observability**

- Traces and metrics exported over OTLP, with the trace carried through the outbox to the consumer
- A counter of transfers by outcome, incremented once the transaction has committed
- A compose profile with Grafana and a provisioned dashboard

**Packaging**

- Container images for the applications (layered jar, Java 25 AOT cache, unprivileged user) and a compose profile that runs the whole system with one command

### Fixed

- A deposit or transfer that would overflow a 64-bit balance is rejected with `422 balance-limit-exceeded` instead of answering `500`
- A failed send to Kafka no longer writes the record, with its wallets and amounts, to the log

### Tests and quality

- Build conventions: Spotless, Error Prone, NullAway and JaCoCo
- Integration tests in their own suite, on real PostgreSQL and Kafka through shared Testcontainers
- ArchUnit rules for the module boundaries
- Invariant queries and a checker that runs them after concurrency tests
- Model-based property test with jqwik
- The build fails when coverage of the domain packages drops below 80%
- Concurrent retries and recovery after a crash, for idempotency
- A k6 constant-arrival-rate scenario and a driver that checks the ledger after every run
- An end-to-end smoke test of the containerised system, run in CI
- CI with a coverage summary and weekly dependency updates

[1.0.0]: https://github.com/HoangThanhMan/ledgerly/releases/tag/v1.0.0
