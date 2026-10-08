# 02. Cấu trúc thư mục và quy ước tổ chức code

> Ký hiệu: **[T0]** là đã có từ lúc khởi tạo, **[Tn]** là được thêm ở tuần *n*, **[chưa có]** là có trong thiết kế nhưng chưa xếp vào tuần nào.

## 1. Cây thư mục đích của repo

```text
ledgerly/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                         # build + test cho mọi push/PR              [T0]
│   │   └── perf-smoke.yml                 # k6 smoke test trên nhánh main            [chưa có]
│   ├── ISSUE_TEMPLATE/                    # mẫu issue: task, bug                     [T1]
│   ├── pull_request_template.md           # checklist tự review                      [T0]
│   └── dependabot.yml                     # cập nhật dependency & actions           [T2]
│
├── build-logic/                           # Gradle convention plugins (included build) [T0]
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── ledgerly.java-conventions.gradle.kts      # toolchain 25, encoding, JUnit [T0]
│       ├── ledgerly.java-library.gradle.kts          # cho module thư viện         [T0]
│       ├── ledgerly.spring-boot-app.gradle.kts       # cho module ứng dụng Boot    [T0]
│       └── ledgerly.quality.gradle.kts               # Spotless, Error Prone, JaCoCo [T2]
│
├── gradle/
│   ├── libs.versions.toml                 # version catalog, nguồn phiên bản duy nhất [T0]
│   └── wrapper/                           # Gradle 9.7.1                              [T0]
│
├── ledger-contracts/                      # thư viện: định nghĩa sự kiện dùng chung   [T0]
│   └── src/main/java/dev/ledgerly/contracts/
│       ├── EventEnvelope.java                                                       [T6]
│       └── events/                        # TransferCompleted, TopUpSettled, ...    [T6]
│
├── ledger-app/                            # ứng dụng chính, modular monolith          [T0]
│   ├── Dockerfile                                                                   [T8]
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/dev/ledgerly/
│       │   ├── LedgerlyApplication.java                                             [T0]
│       │   ├── shared/                    # Money, Ids, ProblemDetails, RequestContext
│       │   ├── ledger/                    # lõi sổ cái kép                            [T3–T4]
│       │   ├── wallet/                    # API ví & chuyển tiền                      [T3]
│       │   ├── idempotency/               # Idempotency-Key                           [T5]
│       │   ├── outbox/                    # outbox writer + relay                     [T6]
│       │   ├── bankgateway/               # HTTP client tới mock-bank                 [T9]
│       │   ├── topup/                     # saga nạp/rút                              [T9]
│       │   └── reconciliation/            # đối soát                                  [T11]
│       ├── main/resources/
│       │   ├── application.yaml                                                     [T0]
│       │   └── db/migration/              # V1__ledger_core.sql, ...                  [T2+]
│       ├── test/java/                     # unit test và ArchitectureTest: không cần Docker
│       └── integrationTest/java/          # Testcontainers, concurrency, chaos        [T2]
│                                          # kèm TestcontainersConfiguration, Test<App>Application
│
├── mock-bank/                             # ngân hàng giả lập                         [T0]
│   ├── Dockerfile                                                                   [T8]
│   └── src/main/java/dev/ledgerly/mockbank/
│
├── notification-consumer/                 # Kafka consumer idempotent                 [T0]
│   ├── Dockerfile                                                                   [T8]
│   └── src/main/java/dev/ledgerly/notification/
│
├── ledger-benchmarks/                     # JMH micro-benchmark (module riêng)        [T11]
│
├── assistant/                             # trợ lý ví: service Python, ngoài Gradle   [A1]
│   ├── pyproject.toml                     # uv, ruff, pyright, pytest
│   ├── Dockerfile
│   ├── src/assistant/
│   │   ├── api/                           # chat API (SSE)                            [A2]
│   │   ├── agent/                         # vòng lặp agent, system prompt             [A2]
│   │   ├── mcp/                           # MCP server và các tool                    [A2]
│   │   ├── retrieval/                     # nạp dữ liệu, tìm kiếm lai                 [A3]
│   │   └── telemetry/                     # span và metric gen_ai.*                   [A2]
│   ├── knowledge/                         # kho tri thức về chính sách (Markdown)     [A3]
│   └── tests/
│
├── evals/                                 # bộ eval của trợ lý                        [A2]
│   ├── tasks/                             # task theo nhóm, có cả safety/             [A2–A3]
│   ├── graders/                           # grader bằng mã, giám khảo bằng model
│   ├── transcripts/                       # transcript đã ghi, cho eval khô trên CI
│   └── results/                           # kết quả thô của từng lượt chạy
│
├── perf/
│   ├── k6/
│   │   ├── transfer-constant-rate.js      # kịch bản chính, tự tạo ví và nạp tiền     [T7]
│   │   └── hot-account.js                 # kịch bản tranh chấp                       [T11]
│   ├── run-baseline.sh                    # chạy N lượt, lưu số liệu và kiểm tra sau mỗi lượt [T7]
│   ├── compact-raw.py                     # rút output thô của k6 còn một dòng mỗi request [T7]
│   └── results/
│       └── 2026-10-08-baseline/           # env.md, và mỗi lượt: summary.json, requests.csv.gz, checks.txt [T7]
│
├── infra/
│   ├── postgres/init/01-create-databases.sql                                        [T0]
│   ├── toxiproxy/toxiproxy.json                                                     [T10]
│   └── grafana/
│       ├── dashboards/ledgerly.json       # dashboard, nạp bằng provisioning          [T7]
│       └── dashboards-provisioning.yaml                                             [T7]
│
├── scripts/
│   ├── invariants.sql                     # truy vấn kiểm tra bất biến I1–I4          [T4]
│   ├── invariants-events.sql              # bất biến I6, chạy tay                     [T6]
│   ├── invariants-agent.sql               # bất biến I8, I9                           [A1]
│   ├── seed.sh                            # tạo ví & nạp tiền cho demo                [chưa có]
│   ├── chaos/                             # script chạy từng kịch bản chaos           [T10]
│   ├── ci/coverage-summary.py             # bảng coverage cho job summary của CI      [T2]
│   └── spikes/locking.sh                  # spike khóa dòng và deadlock               [T1]
│
├── docs/                                  # toàn bộ tài liệu dự án                    [T0]
│   ├── README.md                          # mục lục tài liệu
│   ├── 00-tong-quan-du-an.md … 05-quy-uoc-lam-viec.md
│   ├── adr/                               # Architecture Decision Records
│   ├── weeks/                             # kế hoạch chi tiết từng tuần
│   ├── journal/                           # nhật ký tuần (tự viết mỗi Chủ nhật)       [T1]
│   ├── benchmarks.md                      # phương pháp + bảng kết quả                [T7]
│   ├── images/                            # ảnh chụp dashboard                        [T7]
│   ├── evals.md                           # phương pháp eval + bảng so sánh model     [A3]
│   ├── ai-usage.md                        # "Cách tôi dùng AI"                         [T1]
│   └── research/                          # báo cáo chọn đề tài, báo cáo hướng AI Engineer
│
├── compose.yaml                           # hạ tầng dev (profiles)                    [T0]
├── settings.gradle.kts                                                              [T0]
├── gradle.properties                                                                [T0]
├── gradlew, gradlew.bat                                                             [T0]
├── .editorconfig, .gitattributes, .gitignore                                        [T0]
└── README.md                              # trang giới thiệu dự án                    [T0]
```

## 2. Các module Gradle

| Module | Plugin convention | Loại | Phụ thuộc chính |
|---|---|---|---|
| `ledger-contracts` | `ledgerly.java-library` | Thư viện Java thuần | Không phụ thuộc Spring |
| `ledger-app` | `ledgerly.spring-boot-app` | Ứng dụng Boot | `ledger-contracts`, webmvc, data-jpa, jdbc, flyway, kafka, actuator, validation |
| `mock-bank` | `ledgerly.spring-boot-app` | Ứng dụng Boot | webmvc, jdbc, flyway, actuator, validation |
| `notification-consumer` | `ledgerly.spring-boot-app` | Ứng dụng Boot | `ledger-contracts`, kafka, jdbc, flyway, actuator |
| `ledger-benchmarks` [T11] | `ledgerly.java-conventions` + JMH | Benchmark | `ledger-app` (phần domain) |

`mock-bank` **không** phụ thuộc `ledger-contracts`. Nó là "hệ thống ngoài", chỉ giao tiếp qua HTTP.

## 3. Cấu trúc bên trong một module nghiệp vụ của `ledger-app`

Lấy module `ledger` làm mẫu. Các module khác dùng cùng khuôn.

```text
dev/ledgerly/ledger/
├── LedgerApi.java                 # interface công khai: post(), balance(), history()
├── PostingRequest.java            # record công khai, đầu vào của post()
├── PostingResult.java             # sealed interface: Posted | InsufficientFunds | ...
├── package-info.java              # mô tả trách nhiệm module
└── internal/                      # ⛔ module khác không được import
    ├── domain/                    # thuần Java: Account, Entry, PostingRules
    ├── application/               # LedgerService implements LedgerApi, @Transactional
    ├── persistence/               # AccountRepository, EntryRepository (JdbcClient)
    └── web/                       # (nếu module có REST) controller + DTO
```

| Lớp | Được phép | Không được phép |
|---|---|---|
| Gốc module (API) | records, interface, enum, sealed types | Lớp có logic, annotation Spring |
| `internal.domain` | Java chuẩn, `shared` | Spring, JDBC, Jackson |
| `internal.application` | `@Service`, `@Transactional`, gọi API module khác | Truy cập `internal` của module khác |
| `internal.persistence` | `JdbcClient`, JPA repository | Logic nghiệp vụ |
| `internal.web` | `@RestController`, DTO, validation | Gọi thẳng persistence |

Quy ước này tương thích với Spring Modulith (gốc package là API, package con là internal). Nếu sau này muốn dùng Spring Modulith thì không phải tổ chức lại code.

## 4. Quy ước đặt tên

| Đối tượng | Quy ước | Ví dụ |
|---|---|---|
| Package gốc | `dev.ledgerly.<module>` | `dev.ledgerly.idempotency` |
| Interface API module | `<Module>Api` | `LedgerApi`, `IdempotencyApi` |
| Kết quả nghiệp vụ | sealed interface + records lồng bên trong | `TransferResult.Completed`, `TransferResult.InsufficientFunds` |
| Unit test | `<Class>Test` | `PostingRulesTest` |
| Integration test | `<Feature>IT` | `ConcurrentTransferIT` |
| Bảng DB | `snake_case`, số nhiều | `ledger_transactions`, `outbox_events` |
| Migration | `V<số>__<mô_tả>.sql` | `V1__ledger_core.sql`, `V2__idempotency_keys.sql` |
| Kafka topic | `ledgerly.<aggregate>.v<n>` | `ledgerly.transfers.v1` |
| Metric | `ledgerly.<khu vực>.<tên>` | `ledgerly.outbox.pending` |
| Nhánh git | `<type>/<mô-tả-ngắn>` | `feat/idempotency-key`, `test/chaos-kafka-down` |
| ADR | `NNNN-<tieu-de>.md` | `0004-khoa-bi-quan-co-thu-tu.md` |
| Kết quả benchmark | `perf/results/YYYY-MM-DD-<tên>/` | `perf/results/2026-12-15-hot-account/` |

## 5. Cấu hình ứng dụng

| Tệp | Mục đích |
|---|---|
| `application.yaml` | Mặc định cho môi trường `local`: trỏ tới hạ tầng compose trên `localhost` |
| `application-compose.yaml` [T8] | Khi chạy trong Docker: host là tên service (`postgres`, `kafka`) |
| `application-demo.yaml` [T8] | Môi trường demo trực tuyến |
| Biến môi trường | Ghi đè mọi thứ, ví dụ `SPRING_DATASOURCE_URL`. Không commit secret |

## 6. Cổng và tài nguyên local

| Dịch vụ | Cổng host | Ghi chú |
|---|---|---|
| ledger-app | 8080 | Swagger UI: `/swagger-ui.html` [T8] |
| mock-bank | 8081 | |
| notification-consumer | 8082 | Chỉ có actuator |
| PostgreSQL | **5433** | user/pass `ledgerly`/`ledgerly`. DB `ledger`, `mockbank`, `notification` |
| Kafka | 9092 | Listener `PLAINTEXT_HOST` cho máy host, `kafka:19092` cho mạng nội bộ compose |
| Grafana | 3000 | Profile `observability` [T7] |
| Toxiproxy API | 8474 | Profile `chaos` [T10] |
