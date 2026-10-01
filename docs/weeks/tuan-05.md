# Tuần 5: Idempotency

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 02/11 – 08/11/2026 | 2: Lõi đúng đắn | | 13.5 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Client retry bao nhiêu lần, đồng thời hay tuần tự, và kể cả khi server crash giữa chừng, thì **một `Idempotency-Key` sinh ra tối đa một giao dịch** và mọi lần gọi đều nhận cùng một kết quả.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W05-01 | Flyway `V3__idempotency_keys.sql` | 0.5 | |
| W05-02 | `IdempotencyApi.execute(key, requestHash, action)`: claim (Tx1), rồi action + complete (Tx2) | 3 | |
| W05-03 | Tính `requestHash`: SHA-256 của `method + path + body đã chuẩn hóa` | 1 | |
| W05-04 | Các nhánh: replay (header `Idempotent-Replayed: true`), 422 khi body khác, 409 + `Retry-After`, giành lại khóa sau khi `locked_until` hết hạn | 2 | |
| W05-05 | Lưu cả lỗi nghiệp vụ có tính xác định (422 thiếu tiền) thành `COMPLETED` | 0.5 | |
| W05-06 | Job dọn key hết hạn (`@Scheduled`, xóa theo lô `LIMIT 1000`) | 1 | |
| W05-07 | `IdempotencyConcurrencyIT`: 50 request đồng thời cùng key | 2 | |
| W05-08 | `IdempotencyCrashRecoveryIT`: giả lập crash giữa Tx1 và Tx2 | 2 | |
| W05-09 | **ADR-0005**: lưu key trong PostgreSQL (so sánh với Redis), thiết kế hai pha, TTL 24 giờ | 1.5 | |

## Ghi chú kỹ thuật

### Máy trạng thái của một key

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS: Tx1 INSERT ... ON CONFLICT DO NOTHING
    IN_PROGRESS --> COMPLETED: Tx2 commit (nghiệp vụ + response)
    IN_PROGRESS --> IN_PROGRESS: crash, locked_until hết hạn,<br/>request sau giành lại (CAS)
    COMPLETED --> [*]: hết TTL, job dọn xóa
```

### Vì sao hai pha mà không dùng một transaction?

- **Một transaction**: request thứ hai bị chặn ở unique index cho tới khi request đầu commit. Cách này đơn giản và đúng cho luồng **chỉ có DB**.
- **Hai pha**: cần cho luồng **có gọi mạng ra ngoài** (nạp/rút tiền ở tuần 9), vì không được giữ DB transaction trong lúc chờ ngân hàng. Dùng chung một cơ chế cho mọi endpoint giúp code và test thống nhất.
- Cả hai phương án đều ghi vào ADR-0005, kèm lý do chọn.

### Thiết kế test 50 request đồng thời

Với thiết kế hai pha, request đến khi key đang `IN_PROGRESS` sẽ nhận **409**. Vì vậy test mô phỏng **client đúng chuẩn**:

1. 50 virtual threads gửi cùng key và cùng body tại cùng một thời điểm.
2. Thread nào nhận 409 thì đợi theo `Retry-After` rồi gửi lại.
3. Khẳng định: **đúng 1** dòng `ledger_transactions`, **50/50** response cuối cùng có cùng status và body, và **49** response có header `Idempotent-Replayed: true`.

### Giả lập crash (W05-08)

Trong profile test, đăng ký một `FaultInjector` cho phép ném exception **sau Tx1 và trước Tx2**. Key sẽ kẹt ở `IN_PROGRESS`. Chờ `locked_until` hết hạn (đặt 1 giây trong test), gửi lại request, và khẳng định có đúng một giao dịch được tạo.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `IdempotencyConcurrencyIT` | Như mô tả ở trên |
| `IdempotencyIT.sameKeyDifferentBody` | 422 `idempotency-key-reused` |
| `IdempotencyIT.missingKey` | 400 |
| `IdempotencyIT.replayInsufficientFunds` | Lần 1 nhận 422, lần 2 nhận lại đúng 422 đó (đã lưu) |
| `IdempotencyCrashRecoveryIT` | Đúng 1 giao dịch sau khi phục hồi |
| `IdempotencyCleanupIT` | Key hết hạn bị xóa, key còn hạn giữ nguyên |

## Definition of Done

- [ ] Toàn bộ test xanh
- [ ] Metric `ledgerly.idempotency.replays` tăng đúng
- [ ] ADR-0005 Accepted
- [ ] README có đoạn ngắn "Retry an toàn như thế nào" kèm ví dụ `curl`

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Chuẩn hóa body khó (thứ tự trường JSON) | Hash trên **DTO đã parse**, serialize lại với thứ tự trường cố định, không hash chuỗi thô |
| `locked_until` quá ngắn gây xử lý trùng | Đặt lớn hơn p99.9 của Tx2 (mặc định 30 giây). Lớp bảo vệ cuối là CAS khi giành lại khóa |

## Câu hỏi phỏng vấn tự luyện

1. Key lưu ở đâu, TTL bao lâu, vì sao?
2. Hai request cùng key đến cùng lúc thì chuyện gì xảy ra, chính xác đến từng câu SQL?
3. Server crash khi key đang `IN_PROGRESS` thì sao?
4. Vì sao không lưu key trong Redis?
5. Idempotency ở API và idempotency ở consumer Kafka khác nhau thế nào?
