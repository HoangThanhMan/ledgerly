# 2026-10-08-containers: 300 request mỗi giây, ứng dụng chạy trong container

Một lượt kiểm tra bản đóng gói (`docker compose --profile full`), cùng kịch bản và cùng tốc độ với lô baseline. Đây là **một lượt**, không phải trung vị của ba lượt: dùng để xem bản trong container có khác bản chạy bằng `java -jar` không, không thay cho baseline.

- Ngày chạy: 2026-10-08, 17:50 đến 17:56
- Máy: như lô `2026-10-08-baseline` (laptop, Intel Core i7-11800H, 8 nhân / 16 luồng, 7 GB RAM, Ubuntu 26.04 LTS)
- Docker: Engine 29.7.2. Mọi thứ chạy trong một compose project riêng (`ledgerly-e2e`), profile `full` và `observability`
  - `ledger-app`: image build từ `Dockerfile` (Temurin 25.0.4.1 JRE trên Alpine, AOT cache bật), `cpus: 2`, `-Xmx512m`. JVM thấy 2 CPU (`system.cpu.count = 2`)
  - `notification-consumer`: cùng Dockerfile, `-Xmx256m`, không giới hạn CPU
  - `postgres:18-alpine`, `apache/kafka:4.3.1`, `grafana/otel-lgtm:0.35.0`, cấu hình như baseline
  - Ứng dụng nối tới PostgreSQL và Kafka qua mạng của compose (`postgres:5432`, `kafka:19092`), không qua `docker-proxy`. k6 gọi `localhost:8080`, tức qua cổng publish
- App: nhánh `build/docker-images` tại `b329450`
- Observability: `SPRING_PROFILES_ACTIVE=compose,observability`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1`
- Dữ liệu: database trống trước lượt chạy, ngoài vài giao dịch của `scripts/smoke-test.sh`
- Bộ sinh tải và kịch bản: như baseline (`grafana/k6:2.3.0`, `constant-arrival-rate` 300 request mỗi giây, warm-up 60 giây, đo 300 giây)
- Chạy bằng `COMPOSE_PROJECT=ledgerly-e2e perf/run-baseline.sh 2026-10-08-containers 300 1`
- Những thứ khác chạy trên máy: phiên desktop của tác giả và công cụ điều khiển bài đo

Khác với baseline ở ba điểm, nên hai bên không so trực tiếp được: giới hạn CPU bằng quota của cgroup thay cho `taskset`, đường mạng tới PostgreSQL và Kafka, và JRE trên Alpine (musl) thay cho glibc.

| Lượt | Dùng được | Ghi chú |
|:-:|:-:|---|
| 1 | Có | Mọi threshold đạt, không lượt nào bị bỏ. 102.598 giao dịch, 102.598 sự kiện, 102.598 thông báo, hai script bất biến trả 0 dòng |
