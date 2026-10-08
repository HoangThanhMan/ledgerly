# 2026-10-08-attempt-2: lần đo sạch trên code trước lần sửa cuối

Ba lượt 300 request mỗi giây sau khi sửa bài đo (không chụp ảnh trong lượt đo, k6 nén output). Không phải baseline chính thức, vì sau đó code còn một thay đổi: counter `ledgerly.transfers` chuyển sang tăng sau khi commit. Dùng để thấy hai lần đo cùng cấu hình chênh nhau bao nhiêu. Bảng so sánh: [docs/benchmarks.md](../../../docs/benchmarks.md).

- Ngày chạy: 2026-10-08, 08:19 đến 08:38
- Máy, Docker, JDK, cấu hình ứng dụng, kịch bản: như [baseline](../2026-10-08-baseline/env.md)
- App: nhánh `perf/k6-baseline` tại `ed22f59`
- Database trống lúc bắt đầu, không xóa giữa các lượt
- Output thô của k6 được nén gzip (khoảng 15 MB mỗi lượt), vẫn nằm trên tmpfs
- Chạy bằng bản trước của `perf/run-baseline.sh`: cùng lệnh k6, cùng truy vấn, cùng phép kiểm tra. `checks.txt` chỉ có dòng swap, chưa có dòng nghẽn bộ nhớ
- Áp lực bộ nhớ được lấy mẫu riêng 5 giây một lần từ 08:23:41. Tổng thời gian có tiến trình bị nghẽn vì bộ nhớ trong pha đo: lượt 1 (100 giây cuối) 18 ms, lượt 2 19 ms, lượt 3 49 ms
- CPU của `ledger-app` đọc từ `/proc/<pid>/stat` trong pha đo: 0,35 nhân (lượt 2 và 3)
- Kiểm tra lấy mẫu: Tempo có 1.772 trace `POST /v1/transfers` trong một cửa sổ 60 giây của lượt 3, tức 9,8% của 18.000 request
