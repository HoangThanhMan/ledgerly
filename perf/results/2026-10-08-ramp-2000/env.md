# 2026-10-08-ramp-2000: bài tăng tải, mức 2.000 request mỗi giây

Một bậc của bài thăm dò "thứ gì gãy trước". Không phải baseline. Bảng và cách đọc: [docs/benchmarks.md](../../../docs/benchmarks.md#5-tăng-tải-thứ-gì-gãy-trước).

- Ngày chạy: 2026-10-08, 09:35 đến 09:38
- Máy, Docker, JDK, cấu hình ứng dụng: như [baseline](../2026-10-08-baseline/env.md), trừ những điểm ghi dưới đây
- Kịch bản: `constant-arrival-rate` 2000 request mỗi giây, warm-up 30 giây, đo 120 giây, **một lượt**
- Database: 1.052.171 giao dịch chuyển tiền có sẵn lúc bắt đầu, không xóa giữa các mức
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint`. CPU của nó được đọc từ `/proc/<pid>/stat` mỗi giây
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench WARMUP=30s DURATION=2m perf/run-baseline.sh 2026-10-08-ramp-2000 2000 1`
- `checks.txt` ghi số thông báo lúc consumer **chưa đuổi kịp** (thiếu 91.427). Kiểm tra lại lúc 09:39:04: 1.276.729 giao dịch, 1.276.729 sự kiện, 1.276.729 thông báo, độ trễ của consumer group bằng 0, hai script bất biến trả 0 dòng.
