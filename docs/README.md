# Tài liệu dự án Ledgerly

> Ví điện tử sổ cái kép (double-entry ledger) có bất biến được kiểm chứng: Java 25 · Spring Boot 4.1 · PostgreSQL 18 · Kafka 4.

## Đọc theo thứ tự

| # | Tài liệu | Nội dung | Ai cần đọc |
|:-:|---|---|---|
| 00 | [Tổng quan dự án](00-tong-quan-du-an.md) | Mục tiêu, phạm vi (MoSCoW), mốc, rủi ro, thuật ngữ | Tất cả |
| 01 | [Kiến trúc](01-kien-truc.md) | C4 (context, container, component), mô hình dữ liệu, luồng nghiệp vụ, API, sự kiện, tech stack | Tất cả |
| 02 | [Cấu trúc thư mục](02-cau-truc-thu-muc.md) | Cây thư mục đích, module Gradle, tổ chức package, quy ước đặt tên, cổng | Khi code |
| 03 | [Lộ trình](03-lo-trinh.md) | Gantt, mốc, phân bổ thời gian, quy tắc cắt giảm, nhịp làm việc. 12 tuần gốc và 3 tuần hướng AI | Mỗi tuần |
| 04 | [Chiến lược kiểm thử](04-chien-luoc-kiem-thu.md) | Các tầng test, bất biến I1–I7, ma trận test, phương pháp benchmark | Khi viết test |
| 05 | [Quy ước làm việc](05-quy-uoc-lam-viec.md) | Git, Conventional Commits, PR, quy ước code, chính sách dùng AI | Khi commit |

## Kế hoạch từng tuần

| Tuần | Chủ đề | Mốc |
|:-:|---|:-:|
| [0](weeks/tuan-00.md) | Khởi tạo khung ✅ | M0 |
| [1](weeks/tuan-01.md) | Thiết kế & nghiên cứu 🟡 | M1 |
| [2](weeks/tuan-02.md) | Chất lượng build & schema lõi ✅ | |
| [3](weeks/tuan-03.md) | Domain & chuyển tiền đơn luồng ✅ | |
| [4](weeks/tuan-04.md) | Concurrency & bất biến ✅ | M2 |
| [5](weeks/tuan-05.md) | Idempotency ✅ | |
| [6](weeks/tuan-06.md) | Outbox + Kafka + consumer ✅ | M3 |
| [7](weeks/tuan-07.md) | Observability & benchmark baseline | |
| [8](weeks/tuan-08.md) | Hoàn thiện MVP, `v1.0.0` | M4 |
| [A1](weeks/tuan-a1.md) | Nền cho agent: token theo scope, lệnh chờ xác nhận | |
| [A2](weeks/tuan-a2.md) | MCP server, agent, bộ eval | |
| [A3](weeks/tuan-a3.md) | RAG, an toàn, so sánh model, `v1.1.0` | M5 |
| [9](weeks/tuan-09.md) | mock-bank & saga nạp/rút | |
| [10](weeks/tuan-10.md) | Chaos engineering | |
| [11](weeks/tuan-11.md) | Đối soát & benchmark so sánh | |
| [12](weeks/tuan-12.md) | Ra mắt, hai bản CV, luyện phỏng vấn, `v1.2.0` | M6 |

## Quyết định kiến trúc

Xem [adr/README.md](adr/README.md).

## Số đo hiệu năng

[benchmarks.md](benchmarks.md): baseline 300 request mỗi giây, môi trường đo, giới hạn của con số, và một lần đo hỏng được giữ lại. Dữ liệu từng request nằm trong `perf/results/`.

## Nhật ký và cách dùng AI

- [journal/](journal/README.md): nhật ký tuần, ghi chú đọc, spike.
- [ai-usage.md](ai-usage.md): AI đã gợi ý gì, mình chấp nhận hay bác bỏ gì, kiểm chứng bằng gì.

## Nghiên cứu nền

Hai báo cáo dùng để chọn đề tài nằm trong [research/](research/). Kết luận: chọn **Ledgerly** (modular monolith, sổ cái kép), lấy thêm một số ý từ phương án "Settlement Engine" (JFR pinning, semantic lock cho hold/saga, circuit breaker), và không theo các điểm yếu của phương án đó (4 microservice, bắt buộc Debezium, đặt mục tiêu 10k TPS không có phương pháp đo).
