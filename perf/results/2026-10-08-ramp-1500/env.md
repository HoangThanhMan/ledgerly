# 2026-10-08-ramp-1500: bài tăng tải, mức 1.500 request mỗi giây

Một bậc của bài thăm dò "thứ gì gãy trước". Không phải baseline.

- Ngày chạy: 2026-10-08, 09:32 đến 09:35
- Máy, Docker, JDK, cấu hình ứng dụng: như [baseline](../2026-10-08-baseline/env.md), trừ những điểm ghi dưới đây
- Kịch bản: `constant-arrival-rate` 1500 request mỗi giây, warm-up 30 giây, đo 120 giây, **một lượt**
- Database: 840.286 giao dịch chuyển tiền có sẵn lúc bắt đầu, không xóa giữa các mức
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint`. CPU của nó được đọc từ `/proc/<pid>/stat` mỗi giây
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench WARMUP=30s DURATION=2m perf/run-baseline.sh 2026-10-08-ramp-1500 1500 1`
- `checks.txt` ghi số thông báo lúc consumer **chưa đuổi kịp** (thiếu 51.847). Script lúc đó chỉ chờ outbox xả hết. Một phút sau mức 2.000, số thông báo bằng số giao dịch.
