# Tuần 0: Khởi tạo khung dự án

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 01/10 – 04/10/2026 | 0: Khởi tạo | **M0** | 4–6 giờ | ✅ Hoàn thành (01/10/2026) |

## Mục tiêu

Có một bộ khung **build được, khởi động được, test được**, chưa có chức năng nghiệp vụ nào. Mọi tuần sau chỉ việc thêm code vào đúng chỗ, không phải sửa nền móng.

## Công việc

| ID | Việc | Đầu ra | Trạng thái |
|---|---|---|:-:|
| W00-01 | Chốt đề tài sau khi đọc hai báo cáo nghiên cứu | [00-tong-quan-du-an.md](../00-tong-quan-du-an.md) | ✅ |
| W00-02 | Viết tài liệu kiến trúc, cấu trúc thư mục, lộ trình, kế hoạch tuần | `docs/` | ✅ |
| W00-03 | Đặt tên repo là `ledgerly`, đổi tên thư mục dự án theo tên repo | Thư mục `ledgerly/` | ✅ |
| W00-04 | Gradle 9.7.1 multi-module (Kotlin DSL), version catalog, `build-logic` | `settings.gradle.kts`, `gradle/libs.versions.toml` | ✅ |
| W00-05 | Ba ứng dụng Spring Boot 4.1.1 (Java 25) và một thư viện contracts | `ledger-app`, `mock-bank`, `notification-consumer`, `ledger-contracts` | ✅ |
| W00-06 | Test `contextLoads` cho từng app, chạy bằng Testcontainers (PostgreSQL 18, Kafka 4.3) | `./gradlew build` xanh | ✅ |
| W00-07 | Docker Compose cho hạ tầng dev: PostgreSQL (3 database) và Kafka KRaft | `compose.yaml`, `infra/postgres/init/` | ✅ |
| W00-08 | CI GitHub Actions: build và test trên JDK 25 | `.github/workflows/ci.yml` | ✅ |
| W00-09 | Tệp chuẩn của repo: `.gitignore`, `.gitattributes`, `.editorconfig`, mẫu PR, README | | ✅ |
| W00-10 | ADR-0001 (modular monolith), ADR-0002 (PostgreSQL + SQL tường minh) | `docs/adr/` | ✅ |

## Những gì đã có trong khung

- `ledger-app` có sẵn các package rỗng cho từng module (`shared`, `ledger`, `wallet`, `idempotency`, `outbox`, `bankgateway`, `topup`, `reconciliation`). Mỗi package có `package-info.java` mô tả trách nhiệm của module.
- Virtual threads bật sẵn: `spring.threads.virtual.enabled=true`.
- `spring.jpa.open-in-view=false`, `ddl-auto=validate`. Schema chỉ do Flyway quản lý.
- Mỗi app có lớp `Test<App>Application`, cho phép chạy app bằng Testcontainers mà không cần compose: `./gradlew :ledger-app:bootTestRun`.

## Cách kiểm chứng

```bash
./gradlew build                                   # compile + test (cần Docker)
docker compose up -d                              # PostgreSQL :5433, Kafka :9092
./gradlew :ledger-app:bootRun                     # http://localhost:8080/actuator/health → {"status":"UP"}
```

## Definition of Done

- [x] `./gradlew build` xanh trên máy local
- [x] `docker compose up -d` ra hai container ở trạng thái healthy
- [x] Ba app khởi động và `/actuator/health` trả `UP`
- [x] Tài liệu kế hoạch đầy đủ trong `docs/`
- [ ] Repo đã push lên GitHub (chuyển sang W01-05)
