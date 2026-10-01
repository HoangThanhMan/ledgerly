# Ledgerly

**Ví điện tử theo mô hình sổ cái kép (double-entry ledger), có bất biến được kiểm chứng bằng máy.**

[![CI](https://github.com/HoangThanhMan/ledgerly/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/HoangThanhMan/ledgerly/actions/workflows/ci.yml)
![Java 25](https://img.shields.io/badge/Java-25_LTS-orange)
![Spring Boot 4.1](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F)
![PostgreSQL 18](https://img.shields.io/badge/PostgreSQL-18-336791)
![Kafka 4](https://img.shields.io/badge/Kafka-4.x_KRaft-231F20)

> 🚧 **Trạng thái:** tuần 2, đã có quality gate (format, Error Prone, NullAway) và schema sổ cái với bất biến được database chặn. Chưa có API nghiệp vụ. Xem [lộ trình 12 tuần](docs/03-lo-trinh.md).

Ledgerly là backend ví điện tử (mở ví, chuyển tiền, nạp/rút qua ngân hàng giả lập), xây dựng quanh bốn đảm bảo:

| Đảm bảo | Cách làm | Bằng chứng (sẽ có) |
|---|---|---|
| Không chi tiêu trùng khi có tải đồng thời | Khóa account theo thứ tự id, ràng buộc ở database | Test 200 virtual threads + kiểm tra bất biến |
| API chuyển tiền idempotent | `Idempotency-Key` hai pha theo mô hình Stripe | 50 request đồng thời cùng key → 1 giao dịch |
| DB và sự kiện nhất quán | Transactional outbox + consumer khử trùng | `kill -9` relay: 0 sự kiện mất |
| Đối soát với ngân hàng | Job so khớp sao kê, tự xử lý giao dịch mơ hồ | Test "ghost charge" |

## Kiến trúc

```mermaid
flowchart LR
    client(["Client / k6"]) -->|"HTTP + Idempotency-Key"| app["ledger-app<br/>modular monolith"]
    app --> pg[("PostgreSQL 18")]
    app -->|outbox relay| kafka[["Kafka"]]
    kafka --> consumer["notification-consumer"]
    app -->|HTTP| bank["mock-bank"]
    bank -->|webhook| app
```

Chi tiết: [docs/01-kien-truc.md](docs/01-kien-truc.md).

## Cấu trúc repo

| Module | Mô tả |
|---|---|
| [`ledger-app`](ledger-app) | Ứng dụng chính, gồm các module `ledger`, `wallet`, `idempotency`, `outbox`, `bankgateway`, `topup`, `reconciliation` |
| [`mock-bank`](mock-bank) | Ngân hàng giả lập có thể cấu hình độ trễ và lỗi |
| [`notification-consumer`](notification-consumer) | Kafka consumer idempotent |
| [`ledger-contracts`](ledger-contracts) | Định nghĩa sự kiện dùng chung |
| [`build-logic`](build-logic) | Gradle convention plugins |
| [`docs`](docs) | Tài liệu: kiến trúc, lộ trình, ADR, kế hoạch từng tuần |

## Bắt đầu nhanh

**Yêu cầu:** Docker và JDK 17+ để chạy Gradle. JDK 25 sẽ được Gradle tự tải nếu máy chưa có.

```bash
# 1. Build và chạy toàn bộ test (Testcontainers tự bật PostgreSQL và Kafka)
./gradlew build

# Chỉ unit test (nhanh) hoặc chỉ integration test (Testcontainers)
./gradlew test
./gradlew integrationTest

# Sửa format trước khi commit (build sẽ đỏ nếu lệch format, lỗi null hoặc lỗi Error Prone)
./gradlew spotlessApply

# 2. Bật hạ tầng dev: PostgreSQL ở cổng 5433, Kafka ở cổng 9092
docker compose up -d

# 3. Chạy từng ứng dụng
./gradlew :ledger-app:bootRun              # http://localhost:8080/actuator/health
./gradlew :mock-bank:bootRun               # http://localhost:8081/actuator/health
./gradlew :notification-consumer:bootRun   # http://localhost:8082/actuator/health

# Hoặc chạy một app với Testcontainers, không cần compose
./gradlew :ledger-app:bootTestRun
```

## Tài liệu

- [Tổng quan dự án](docs/00-tong-quan-du-an.md): mục tiêu, phạm vi, mốc, rủi ro
- [Kiến trúc](docs/01-kien-truc.md): C4, mô hình dữ liệu, luồng nghiệp vụ, API
- [Cấu trúc thư mục](docs/02-cau-truc-thu-muc.md)
- [Lộ trình 12 tuần](docs/03-lo-trinh.md) và [kế hoạch từng tuần](docs/weeks/)
- [Chiến lược kiểm thử](docs/04-chien-luoc-kiem-thu.md)
- [Quy ước làm việc](docs/05-quy-uoc-lam-viec.md)
- [Architecture Decision Records](docs/adr/README.md)
