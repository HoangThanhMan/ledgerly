# 2026-10-08-ramp-0600-4cpu: mức 600 với 4 nhân

Một lượt để thử giả thuyết "hai nhân là quá ít lúc dồn việc". Kết quả: vẫn có đợt đứng, dài hơn. Xem [docs/benchmarks.md](../../../docs/benchmarks.md#44-các-đợt-đứng-biết-gì-và-chưa-biết-gì).

- Ngày chạy: 2026-10-08, 09:42 đến 09:45
- Máy, Docker, JDK, cấu hình ứng dụng: như [baseline](../2026-10-08-baseline/env.md), trừ những điểm ghi dưới đây
- Kịch bản: `constant-arrival-rate` 600 request mỗi giây, warm-up 30 giây, đo 120 giây, **một lượt**
- Database: 1.276.729 giao dịch chuyển tiền có sẵn lúc bắt đầu, không xóa giữa các mức
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint`. CPU của nó được đọc từ `/proc/<pid>/stat` mỗi giây
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench WARMUP=30s DURATION=2m perf/run-baseline.sh 2026-10-08-ramp-0600-4cpu 600 1`
- Khác với `2026-10-08-ramp-0600`: `taskset -c 0-3` thay cho `0,1`. Và hai điều không mong muốn: database lớn gấp đôi, ứng dụng vừa khởi động lại
