# 2026-10-08-no-export: một lượt không xuất metric và trace

Một lượt 300 request mỗi giây, để so với [baseline](../2026-10-08-baseline/env.md). **Chỉ một lượt**, không đủ để kết luận về độ trễ. Bảng so sánh: [docs/benchmarks.md](../../../docs/benchmarks.md#6-chi-phí-của-việc-xuất-metric-và-trace).

- Ngày chạy: 2026-10-08, 09:19 đến 09:25, ngay sau lượt 5 của baseline, trên cùng database (511.360 giao dịch lúc bắt đầu)
- Khác với baseline đúng một điều: hai ứng dụng chạy **không có** profile `observability`, nên không gửi metric hay trace đi đâu. Việc tạo span trong tiến trình vẫn diễn ra (lấy mẫu 10%)
- Vì không có gì được gửi tới Prometheus, `server-metrics.json` toàn `null`
- `ledger-app` có thêm cờ `-Xlog:gc,safepoint` để chẩn đoán
- CPU của `ledger-app` trong pha đo, đọc từ `/proc/<pid>/stat` mỗi giây: 0,32 nhân
