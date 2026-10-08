# 2026-10-08-attempt-1: lần đo bị nhiễu

**Không dùng làm baseline.** Giữ lại để thấy một lần đo hỏng trông như thế nào.

- Ngày chạy: 2026-10-08, 07:58 đến 08:17
- Máy, Docker, JDK, cấu hình ứng dụng, kịch bản: như [baseline](../2026-10-08-baseline/env.md)
- App: nhánh `perf/k6-baseline` tại `ed22f59` (trước lần sửa counter `ledgerly.transfers`)
- Khác với baseline, và là nguyên nhân gây nhiễu:
  - Script đo mở một Chrome headless để chụp dashboard ở giây 330 của lượt 2.
  - k6 ghi output thô **không nén** (ước 400 MB mỗi lượt) vào một thư mục trên tmpfs, tức là vào RAM.
  - Chưa ghi lại áp lực bộ nhớ (`/proc/pressure/memory`), nên `checks.txt` ở đây không có hai dòng cuối.
- Thiếu file: `run-1/k6-summary.txt`. Lần đo lại đã ghi đè nó vì bước đổi tên thư mục bị lỗi mà không được kiểm tra. Cùng số liệu vẫn có trong `run-1/summary.json` và `run-1/requests.csv.gz`.
- `server-metrics.json` dùng cửa sổ 5 phút cuối của mỗi lượt.
