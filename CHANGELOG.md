# Changelog

All notable changes to this project are listed here, grouped from the [Conventional Commits](https://www.conventionalcommits.org/) titles of the merged pull requests. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-08

The first release: wallets, deposits and transfers on a double-entry ledger, safe under concurrency and retries, with events delivered through an outbox to an idempotent consumer.

### Added

**Ledger**

- Core ledger schema with invariants enforced by the database: append-only entries, entries of a transaction that sum to zero, no negative wallet balance ([#74](https://github.com/HoangThanhMan/ledgerly/pull/74))
- `Money` in minor units and the posting rules, as plain Java ([#79](https://github.com/HoangThanhMan/ledgerly/pull/79))
- JDBC repositories and the ledger service ([#80](https://github.com/HoangThanhMan/ledgerly/pull/80))
- Account rows are locked in `id` order before a posting, with a lock timeout that answers `503` ([#86](https://github.com/HoangThanhMan/ledgerly/pull/86))

**Wallet API**

- Open a wallet, read its balance and entry history, transfer, deposit, with RFC 9457 Problem Details for errors ([#83](https://github.com/HoangThanhMan/ledgerly/pull/83))
- Transfers and deposits are idempotent per `Idempotency-Key` ([#93](https://github.com/HoangThanhMan/ledgerly/pull/93))
- An OpenAPI document that describes every operation, parameter and problem, served with Swagger UI and kept as `ledger-app/openapi.yaml` ([#114](https://github.com/HoangThanhMan/ledgerly/pull/114))

**Idempotency**

- `idempotency_keys` table and repository ([#91](https://github.com/HoangThanhMan/ledgerly/pull/91))
- An action runs at most once per key, in two phases with a lease and a fencing token ([#92](https://github.com/HoangThanhMan/ledgerly/pull/92))
- Expired keys are deleted in batches on a schedule ([#94](https://github.com/HoangThanhMan/ledgerly/pull/94))

**Events**

- Event envelope, `TransferCompleted` and topic names shared by producer and consumer ([#98](https://github.com/HoangThanhMan/ledgerly/pull/98))
- A `TransferCompleted` event is written in the transfer's own transaction ([#99](https://github.com/HoangThanhMan/ledgerly/pull/99))
- A relay publishes outbox events to Kafka at least once ([#100](https://github.com/HoangThanhMan/ledgerly/pull/100))
- `notification-consumer` handles each event id once ([#101](https://github.com/HoangThanhMan/ledgerly/pull/101))

**Observability**

- Traces and metrics exported over OTLP, with the trace carried through the outbox to the consumer ([#106](https://github.com/HoangThanhMan/ledgerly/pull/106))
- A counter of transfers by outcome, incremented once the transaction has committed ([#107](https://github.com/HoangThanhMan/ledgerly/pull/107))
- A compose profile with Grafana and a provisioned dashboard ([#108](https://github.com/HoangThanhMan/ledgerly/pull/108))

**Packaging**

- Container images for the applications (layered jar, Java 25 AOT cache, unprivileged user) and a compose profile that runs the whole system with one command ([#115](https://github.com/HoangThanhMan/ledgerly/pull/115))

### Fixed

- A deposit or transfer that would overflow a 64-bit balance is rejected with `422 balance-limit-exceeded` instead of answering `500` ([#114](https://github.com/HoangThanhMan/ledgerly/pull/114))

### Tests and quality

- Build conventions: Spotless, Error Prone, NullAway and JaCoCo ([#72](https://github.com/HoangThanhMan/ledgerly/pull/72))
- Integration tests in their own suite, on real PostgreSQL and Kafka through shared Testcontainers ([#73](https://github.com/HoangThanhMan/ledgerly/pull/73))
- ArchUnit rules for the module boundaries ([#82](https://github.com/HoangThanhMan/ledgerly/pull/82))
- Invariant queries and a checker that runs them after concurrency tests ([#85](https://github.com/HoangThanhMan/ledgerly/pull/85))
- Model-based property test with jqwik ([#87](https://github.com/HoangThanhMan/ledgerly/pull/87))
- The build fails when coverage of the domain packages drops below 80% ([#88](https://github.com/HoangThanhMan/ledgerly/pull/88))
- Concurrent retries and recovery after a crash, for idempotency ([#95](https://github.com/HoangThanhMan/ledgerly/pull/95))
- A k6 constant-arrival-rate scenario and a driver that checks the ledger after every run ([#109](https://github.com/HoangThanhMan/ledgerly/pull/109))
- An end-to-end smoke test of the containerised system, run in CI ([#115](https://github.com/HoangThanhMan/ledgerly/pull/115))
- CI with a coverage summary and weekly dependency updates ([#75](https://github.com/HoangThanhMan/ledgerly/pull/75))

[1.0.0]: https://github.com/HoangThanhMan/ledgerly/releases/tag/v1.0.0
