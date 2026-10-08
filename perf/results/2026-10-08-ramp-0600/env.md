# 2026-10-08-ramp-0600: bài tăng tải, mức 600 request mỗi giây

Một bậc của bài thăm dò "thứ gì gãy trước". Không phải baseline.

- Ngày chạy: 2026-10-08, 09:26 đến 09:29
- Máy, Docker, JDK, cấu hình ứng dụng: như [baseline](../2026-10-08-baseline/env.md), trừ những điểm ghi dưới đây
- Kịch bản: `constant-arrival-rate` 600 request mỗi giây, warm-up 30 giây, đo 120 giây, **một lượt**
- Database: 613.897 giao dịch chuyển tiền có sẵn lúc bắt đầu, không xóa giữa các mức
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint`. CPU của nó được đọc từ `/proc/<pid>/stat` mỗi giây
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench WARMUP=30s DURATION=2m perf/run-baseline.sh 2026-10-08-ramp-0600 600 1`
- Ứng dụng vừa khởi động lại trước mức này.
