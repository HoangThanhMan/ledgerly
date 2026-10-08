# 2026-10-08-baseline: baseline 300 request mỗi giây

Lô đo chính thức của tuần 7. Bảng kết quả và cách đọc: [docs/benchmarks.md](../../../docs/benchmarks.md#4-baseline-300-request-mỗi-giây).

- Ngày chạy: 2026-10-08. Lượt 1 đến 3: 08:44 đến 09:03. Lượt 4: 09:03 đến 09:09. Lượt 5: 09:11 đến 09:17
- Máy: laptop, Intel Core i7-11800H, 8 nhân / 16 luồng, 7 GB RAM, 4 GB swap, SSD NVMe WDC PC SN530 512 GB (ext4), Ubuntu 26.04 LTS, kernel 7.0.0-34-generic, cắm sạc, governor `powersave`
- Docker: Engine 29.7.2, Compose 5.5.0. Không container nào bị giới hạn CPU hay RAM
  - `postgres:18-alpine` (PostgreSQL 18.6), cấu hình mặc định của image: `shared_buffers=128MB`, `synchronous_commit=on`, `max_connections=100`
  - `apache/kafka:4.3.1`, một broker KRaft
  - `grafana/otel-lgtm:0.35.0`
  - Ứng dụng nối tới PostgreSQL và Kafka qua cổng publish của Docker (`localhost:5433`, `localhost:9092`), tức qua `docker-proxy`
- JDK: Temurin 25.0.4.1+1 LTS
  - `ledger-app`: `taskset -c 0,1 java -Xmx512m -jar ...` (hai nhân vật lý khác nhau, luồng anh em 8 và 9 không được dành riêng). G1 (mặc định), heap ban đầu 114 MB. `java -jar` ngoài Docker
  - `notification-consumer`: `java -Xmx256m -jar ...`, không ghim
- App: nhánh `perf/k6-baseline` tại `0eecd34` (code ứng dụng giống hệt bản được merge). `spring.threads.virtual.enabled=true`, HikariCP `maximumPoolSize=10` (mặc định), một instance, relay quét 200 ms một lần, lô 100
- Observability: `SPRING_PROFILES_ACTIVE=observability`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1`, metric gửi 10 giây một lần. Kiểm tra ở lô trước: Tempo có 1.772 trace cho 18.000 request, tức 9,8%
- Dữ liệu: database trống trước lượt 1, **không xóa giữa các lượt**. Mỗi lượt thêm 1.000 ví (mỗi ví 10.000.000 VND) và khoảng 102.500 giao dịch chuyển tiền. 5% request lặp `Idempotency-Key`
- Bộ sinh tải: k6 v2.3.0 (`grafana/k6:2.3.0`, `--network host`), chạy **cùng máy**, không giới hạn CPU. Output thô nén gzip, ghi ra ổ đĩa (`build/k6-raw/`), khoảng 15 MB mỗi lượt
- Kịch bản: `constant-arrival-rate` 300 request mỗi giây, warm-up 60 giây, đo 300 giây, 200 người dùng ảo cấp sẵn, tối đa 1.000
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench perf/run-baseline.sh 2026-10-08-baseline 300 3`, rồi hai lần `FIRST_RUN=4` và `FIRST_RUN=5` với một lượt mỗi lần. Hai dòng nghẽn CPU và ổ đĩa trong `checks.txt` chỉ có từ lượt 4, vì script được bổ sung giữa chừng
- Những thứ khác chạy trên máy: phiên desktop của tác giả (Chrome, VS Code) và công cụ AI điều khiển bài đo. Trong lượt 4 có một ứng dụng desktop được mở (GNOME Settings, 09:08). Ở lượt 5 có thêm một bộ lấy mẫu gọi `top` 5 giây một lần

Trạng thái từng lượt:

| Lượt | Dùng được | Ghi chú |
|:-:|:-:|---|
| 1 | Có | Mọi threshold đạt |
| 2 | Có | k6 thoát với mã 99: 200 lượt bị bỏ, tất cả trong warm-up (giây 46 đến 48). Pha đo đủ 90.001 request |
| 3 | Có | Mọi threshold đạt |
| 4 | Không | Nhiều đợt đứng trong pha đo, p99 2,31 giây, 1.052 lượt bị bỏ |
| 5 | Không | Một đợt đứng 2 giây trong pha đo (124 lượt bị bỏ) và một đợt trong warm-up (251 lượt) |
