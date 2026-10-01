# Cách tôi dùng AI

> Quy tắc ở [05-quy-uoc-lam-viec §7](05-quy-uoc-lam-viec.md#7-chính-sách-dùng-ai): AI là đồng nghiệp để hỏi và review, không sinh code mà mình không hiểu, và **không viết ADR thay mình**.

Mỗi mục ghi: hỏi AI gì, AI gợi ý gì, mình quyết định gì (chấp nhận hay bác bỏ, vì sao), và kiểm chứng bằng gì. Mục mới thêm lên **đầu** file.

```markdown
### YYYY-MM-DD: <chủ đề>
- Hỏi AI: ...
- AI gợi ý: ...
- Quyết định: chấp nhận / bác bỏ / sửa lại, vì ...
- Kiểm chứng: test, log hoặc PR
```

---

### 2026-10-01: Giao toàn bộ tuần 1 cho AI
- Hỏi AI: "Mọi việc để bạn làm, hãy thực hiện cho xong tuần 1". Lệnh này thay cho lựa chọn trước đó (AI chỉ chuẩn bị, mình viết lập luận, mình merge).
- AI gợi ý: viết trọn ADR-0003 (Accepted), 6 ghi chú đọc, nhật ký W41, kết luận spike, đáp án tham khảo cho 5 câu hỏi M1, và sửa §7.3 cho khớp thực tế.
- Quyết định: chấp nhận. §7.3 đổi từ "không dùng AI viết ADR thay mình" thành "AI được soạn nháp ADR khi được yêu cầu, tác giả phải review, hiểu và chịu trách nhiệm từng dòng". Ghi chú hai cuốn sách (SDI Vol. 2, DDIA) viết từ kiến thức chung, chưa đối chiếu sách.
- Kiểm chứng: PR #68–#71. Merge PR, bảo vệ nhánh `main`, chuyển repo public và tạo Projects board vẫn phải do mình làm, vì quyền của AI bị chặn.

### 2026-10-01: Mẫu issue, khung ghi chú đọc và nhật ký (W01-07, W01-01, W01-09)
- Hỏi AI: làm W01-07, và chuẩn bị cho W01-01, W01-09.
- AI gợi ý: issue form `task` và `bug` (bug có ô chọn bất biến I1–I7). Thư mục `docs/journal/` có khung cho 6 tài liệu đọc và khung nhật ký tuần, kèm câu hỏi gợi ý. AI không viết nội dung ghi chú hay cảm nhận thay mình.
- Quyết định: chấp nhận. Sau đó (xem mục trên) AI viết luôn nội dung ghi chú và nhật ký.
- Kiểm chứng: PR #71. Tạo thử một issue bằng mẫu `task` sau khi merge.

### 2026-10-01: Spike khóa SQL (W01-08)
- Hỏi AI: làm spike khóa dòng và deadlock của tuần 1.
- AI gợi ý: script `scripts/spikes/locking.sh` chạy hai phiên `psql` theo kịch bản thời gian cố định, có ba kịch bản (khóa ngược thứ tự, cùng thứ tự, một câu `ORDER BY id FOR UPDATE`). AI chạy script và ghi log thật vào `docs/journal/spike-locking.md`.
- Quyết định: giữ script để tái hiện được. AI thêm kịch bản 4 (`UPDATE` ngược chiều cũng deadlock) và viết kết luận. Từ đó rút ra quy tắc cho W04-01.
- Kiểm chứng: PR #70, log deadlock phía server, 5 lần chạy cho cùng kết quả.

### 2026-10-01: ADR-0003, biểu diễn tiền (W01-03)
- Hỏi AI: làm W01-03.
- AI gợi ý: vì quy tắc "không dùng AI viết ADR thay mình", AI hỏi lại cách làm. Mình chọn để AI chạy thí nghiệm và dựng khung, còn lập luận và quyết định do mình viết.
- Quyết định: ban đầu để mình viết lập luận, sau đó giao cho AI viết trọn. Chọn `long` theo đơn vị nhỏ nhất + ISO 4217, một cột `amount` có dấu, API trả chuỗi số nguyên. Bác bỏ `BigDecimal` (cấp phát object, bẫy `equals`) và `amount` + `direction` (phải tra loại account mới biết tăng hay giảm).
- Kiểm chứng: PR #69. Bằng chứng là output thật của jshell 25, Node 20 và PostgreSQL 18.6.

### 2026-10-01: Review tài liệu kiến trúc (W01-02)
- Hỏi AI: review `01-kien-truc.md`, tập trung vào mô hình dữ liệu và luồng 6.1, 6.2.
- AI gợi ý: 8 điểm. Hai điểm quan trọng: (1) luồng idempotency hai pha cho phép một key sinh ra hai giao dịch khi Tx2 chạy lâu hơn `locked_until`, sửa bằng `lease_token` (fencing token); (2) `reconciliation` cần gọi `topup` nhưng ma trận phụ thuộc cấm. Ngoài ra có nhận xét cho ADR-0001 và ADR-0002 nhưng AI không sửa ADR.
- Quyết định: chấp nhận cả 8 điểm. Sửa luôn hai chỗ trong ADR-0002 (MySQL **không có** deferred constraint, thêm nguồn số liệu Stack Overflow 2025).
- Kiểm chứng: PR #68. Ca *zombie* được thêm vào kế hoạch `IdempotencyCrashRecoveryIT` (tuần 5).

### 2026-10-01: Repo GitHub và backlog (W01-05, W01-06)
- Hỏi AI: tạo repo, push code, tạo label, milestone và issue cho tuần 2–8.
- AI gợi ý: label theo `type:` / `area:` / `week:` / `moscow:`, milestone M1–M5 theo [lộ trình](03-lo-trinh.md#3-mốc-và-điều-kiện-qua-mốc), mỗi task trong file tuần là một issue (67 issue cho tuần 1–8).
- Quyết định: repo để **private** cho tới khi mình tự chuyển sang public. Mình tự merge PR sau khi tự review. Bảo vệ nhánh `main` và tạo Projects board do mình tự làm (AI không có quyền).
- Kiểm chứng: issue #1–#67 khớp bảng công việc trong `docs/weeks/tuan-01.md` … `tuan-08.md`.
