# 2026-10-08-ramp-1000-no-jdbc-obs: mức 1.000, tắt observation của SQL

**Phép thử không kết luận được.**

- Ngày chạy: 2026-10-08, 09:50 đến 09:54
- Máy, Docker, JDK, cấu hình ứng dụng: như [baseline](../2026-10-08-baseline/env.md), trừ những điểm ghi dưới đây
- Kịch bản: `constant-arrival-rate` 1000 request mỗi giây, warm-up 60 giây, đo 120 giây, **một lượt**
- Database: 1.420.292 giao dịch chuyển tiền có sẵn lúc bắt đầu, không xóa giữa các mức
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint`. CPU của nó được đọc từ `/proc/<pid>/stat` mỗi giây
- Chạy bằng `COMPOSE_PROJECT=ledgerly-bench WARMUP=60s DURATION=2m perf/run-baseline.sh 2026-10-08-ramp-1000-no-jdbc-obs 1000 1`
- Khác với `2026-10-08-ramp-1000` ở **bốn** điểm, nên không so sánh được: `JDBC_DATASOURCE_PROXY_ENABLED=false`, ứng dụng vừa khởi động lại, database lớn gấp đôi, và máy bận hơn (27,5 giây nghẽn CPU so với 7,4 giây)
- Vì không còn timer `jdbc.query`, `sql_p99_ms` trong `server-metrics.json` là `null`
