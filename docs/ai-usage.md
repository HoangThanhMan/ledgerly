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

### 2026-10-04: GitHub Projects board (W01-06)
- Hỏi AI: xem tiến độ dự án. Sau đó mình tự cấp scope `project` cho `gh` (xác nhận mã thiết bị trên trình duyệt) để AI làm nốt W01-06.
- AI gợi ý: tạo board [Ledgerly](https://github.com/users/HoangThanhMan/projects/3) liên kết với repo, đổi field `Status` thành năm cột `Backlog`, `Todo`, `In progress`, `Review`, `Done` theo [lộ trình §7](03-lo-trinh.md#7-theo-dõi-tiến-độ-trên-github). Đưa cả 67 issue vào: issue đã đóng vào `Done`, việc còn lại của tuần 3 và #5 vào `Todo`, tuần 4–8 vào `Backlog`. PR dependabot #77 vào `Review`. Thêm một view dạng board bên cạnh view dạng bảng.
- Quyết định: board để **private** như repo. Các workflow tự chuyển cột (`Item closed`, `Pull request merged`) đang tắt và chỉ bật được trên giao diện, nên việc đó còn lại cho mình.
- Kiểm chứng: `gh project item-list 3` cho 68 mục: 40 `Backlog`, 6 `Todo`, 2 `Review` (#6 và PR #77), 20 `Done`.

### 2026-10-03: Tuần 3, `Money`, `PostingRules`, repository và `LedgerService` (W03-01 … W03-04)
- Hỏi AI: làm phần domain và persistence của tuần 3, rồi commit và merge vào `main`.
- AI gợi ý:
  - `Money` giữ `long` đơn vị nhỏ nhất (ADR-0003), tràn số ném `ArithmeticException`, khác currency ném `IllegalArgumentException`. `parsePositive` chỉ nhận chữ số ASCII vì `Long.parseLong` nhận cả chữ số Unicode khác.
  - `PostingRules` là Java thuần. Posting sai dạng (ít hơn hai, số 0, trùng account, tổng khác 0) là lỗi lập trình nên ném exception. Account không tồn tại, sai currency, thiếu tiền là kết quả nghiệp vụ, trả về dạng sealed `PostingResult` để caller phải `switch` đủ nhánh.
  - Repository dùng `JdbcClient` (ADR-0002). `findForPosting` đọc account theo thứ tự `id`, nên sau này thêm khóa chỉ cần `FOR UPDATE` mà không deadlock. Lịch sử dùng keyset trên index `(account_id, id)`.
  - `LedgerApi` là cổng duy nhất của module, `LedgerService` package-private và mỗi method có ranh giới `@Transactional` riêng.
- Quyết định: chấp nhận. Chưa có khóa dòng và idempotency, đúng phạm vi tuần 3.
- Kiểm chứng: `./gradlew build` xanh. `MoneyTest` 24, `PostingRulesTest` 12, `LedgerServiceIT` 7 (posting cập nhật số dư và entry, bị từ chối thì không đổi gì, keyset không trùng không sót).

### 2026-10-03: Chuyển comment trong code sang tiếng Anh
- Hỏi AI: kiểm tra toàn dự án, mọi comment phải viết bằng tiếng Anh, chỉ mô tả code, không tham chiếu kế hoạch tuần (W0x-yy, "tuần N").
- AI gợi ý: dịch comment trong Java (Javadoc, `package-info`), Gradle Kotlin DSL, SQL migration, YAML, TOML, Python và shell. Bỏ mọi mã task và "Tuần triển khai". Thay mã bất biến (I1, I2, P2) bằng mô tả. Giữ tham chiếu tới ADR và tài liệu kiến trúc vì đó là lý do thiết kế, không phải kế hoạch. Dịch luôn `description` của các module Gradle và output của `coverage-summary.py` (dòng "Tuần 2 chỉ báo cáo…" bị bỏ). Không đổi output của `scripts/spikes/locking.sh` (log trong `docs/journal/spike-locking.md` trích nguyên văn) và issue form (người dùng đọc, cùng ngôn ngữ với tài liệu).
- Quyết định: chấp nhận. Sửa comment trong V1, V2 làm đổi checksum Flyway: chấp nhận vì chưa có môi trường nào ngoài máy dev. Database compose cũ cần `docker compose down -v`.
- Kiểm chứng: `./gradlew build` xanh (14 integration test). Gặp một lỗi lạ của Kotlin DSL: khi `ledgerly.spring-boot-app.gradle.kts` dài đúng 1071 byte thì `build-logic:compileKotlin` không sinh accessor. Lỗi tái hiện ổn định, đổi độ dài 1 ký tự là hết, nên đã viết lại câu comment.

### 2026-10-01: Tuần 2, chất lượng build và schema lõi (W02-01 … W02-09)
- Hỏi AI: "Thực hiện luôn tuần 2".
- AI gợi ý: 4 PR code xếp chồng (#72–#75) và 1 PR tài liệu (#76). Phiên bản plugin lấy bản mới nhất trên Maven Central và Gradle Plugin Portal ngày 01/10/2026, rồi kiểm chứng bằng build thật trên JDK 25.
- Quyết định:
  - **Bác bỏ** `failOnNoDiscoveredTests = false` để `src/test` chứa được lớp hỗ trợ. Thay vào đó chuyển lớp hỗ trợ sang `src/integrationTest` và trỏ `bootTestRun` sang suite đó. Giữ lưới an toàn của Gradle.
  - **Bác bỏ** image `apache/kafka-native` trong Testcontainers sau khi đo: segfault 2/15 lần khởi động. Dùng `apache/kafka` (JVM), 15/15.
  - **Thêm ngoài kế hoạch:** chặn `TRUNCATE`, cho `ledger_transactions` chỉ được thêm, ràng buộc "giao dịch phải có entry" và kiểm tra mã ISO 4217. Mỗi ràng buộc có test riêng.
  - Merge PR bị chặn quyền, nên PR được xếp chồng để tác giả merge một lượt.
- Kiểm chứng: lỗi null, Error Prone và format cố ý thêm vào đều làm build đỏ. `SchemaConstraintsIT` đi qua hai bước RED (bảng chưa có, rồi bảng chưa có ràng buộc) trước khi GREEN 13/13. Đếm container: 2 context, 1 PostgreSQL. 5 lượt suite có Kafka xanh liên tiếp.

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
