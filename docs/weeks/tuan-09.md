# Tuần 9: mock-bank và saga nạp/rút tiền

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 30/11 – 06/12/2026 | 4: Mở rộng | | 15 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Xử lý đúng khi giao dịch phải đi qua **một hệ thống ngoài không tin cậy**: chậm, lỗi, trả lời mơ hồ, gọi lại hai lần. Thể hiện được **bút toán bù trừ** của saga.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W09-01 | `mock-bank`: `POST /payments` (khử trùng theo `merchantRef`), `GET /payments/{merchantRef}`, `GET /statements?date=` | 2 | |
| W09-02 | `mock-bank`: chế độ bất đồng bộ (trả `PROCESSING` rồi gọi webhook), cấu hình hành vi qua `POST /admin/behaviour` | 1.5 | |
| W09-03 | `bankgateway`: interface `@HttpExchange` + `RestClient`, timeout kết nối và timeout đọc. Ánh xạ sang sealed `BankResult` (`Succeeded`, `Declined`, `Unknown`) | 2 | |
| W09-04 | Retry bằng `@Retryable` (Spring Framework 7) có backoff + jitter, **chỉ** cho lệnh idempotent | 0.5 | |
| W09-05 | `topup`: Flyway `V5`, `POST /v1/topups` → `PENDING`. `SagaWorker` lấy việc bằng `SKIP LOCKED` theo `next_attempt_at`, chuyển trạng thái bằng CAS | 3 | |
| W09-06 | Webhook `/internal/bank-callbacks`: xác thực HMAC bằng secret chung, chuyển trạng thái idempotent | 1.5 | |
| W09-07 | Rút tiền có bù trừ: `HELD → PAID_OUT / COMPENSATED / UNKNOWN` | 2.5 | |
| W09-08 | Test bằng WireMock chạy trong Testcontainers (giả lập bank chậm, lỗi, trả lời mơ hồ) | 1 | |
| W09-09 | **ADR-0008**: saga orchestration (so với 2PC và TCC Try-Confirm/Cancel) | 1 | |

## Ghi chú kỹ thuật

### Cấu hình hành vi của mock-bank

| Tham số | Ý nghĩa | Dùng để kiểm thử |
|---|---|---|
| `declineRate` | Tỉ lệ từ chối | Nhánh `FAILED` / `COMPENSATED` |
| `extraLatencyMs` | Độ trễ thêm | Timeout, retry |
| `asyncRate` | Tỉ lệ trả `PROCESSING` rồi gọi webhook sau | Webhook và worker chạy đua nhau |
| `ghostChargeRate` | **Đã thu tiền nhưng trả 500** | Trạng thái `UNKNOWN` và đối soát (tuần 11) |
| `duplicateCallbackRate` | Gọi webhook hai lần | Webhook idempotent |

### Chuyển trạng thái bằng compare-and-set

```sql
UPDATE topups SET status = 'SETTLED', bank_ref = :ref
WHERE id = :id AND status IN ('PENDING', 'UNKNOWN');
-- 0 dòng: bên khác (webhook hoặc worker) đã xử lý → không ghi bút toán lần nữa
-- 1 dòng: ghi bút toán + outbox TopUpSettled trong CÙNG transaction
```

### Bảng quyết định khi gọi bank

| Phản hồi của bank | Hành động |
|---|---|
| `SUCCEEDED` | Ghi bút toán, chuyển `SETTLED`/`PAID_OUT` |
| `DECLINED` | Nạp: `FAILED`. Rút: chạy **bù trừ**, trả tiền về ví, `COMPENSATED` |
| `PROCESSING` | Giữ nguyên, chờ webhook hoặc lần poll tiếp theo |
| Timeout / 5xx / lỗi kết nối | **Không biết** bank đã thu chưa. Retry (bank khử trùng theo `merchantRef`). Hết lượt thì `UNKNOWN` |
| 4xx (request sai) | Lỗi lập trình: `FAILED` + log ERROR + alert |

> **Điểm then chốt khi phỏng vấn:** timeout **không có nghĩa là thất bại**. Hoàn tiền ngay khi timeout có thể khiến khách được nhận tiền hai lần.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `TopUpSagaIT.happyPath` | `PENDING → SETTLED`, số dư tăng, 1 sự kiện |
| `TopUpSagaIT.declined` | `FAILED`, số dư không đổi |
| `TopUpSagaIT.timeoutThenSuccess` | Retry, `merchantRef` không đổi, chỉ ghi có 1 lần |
| `TopUpSagaIT.webhookAndWorkerRace` | Webhook và worker cùng xử lý: đúng 1 bút toán |
| `WithdrawalSagaIT.declinedCompensates` | Tiền về lại ví. Sổ cái có đủ cặp bút toán giữ và hoàn |
| `BankCallbackIT.invalidSignature` | 401, không đổi trạng thái |

## Definition of Done

- [ ] Toàn bộ test xanh, bất biến I1–I4 vẫn đúng
- [ ] Sơ đồ trạng thái trong README khớp với code
- [ ] ADR-0008 Accepted

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Quá tải trong tuần (15 giờ) | Cắt W09-07 (rút tiền) trước, sau đó cắt webhook (chỉ dùng polling) |
| WireMock chưa tương thích Jetty/Boot 4 trên classpath | Chạy WireMock **trong container** nên không xung đột classpath |

## Câu hỏi phỏng vấn tự luyện

1. Vì sao chọn saga thay vì 2PC hay TCC?
2. Bank timeout nhưng thực tế đã trừ tiền thì sao?
3. Webhook đến trước khi worker nhận được response thì sao?
4. Vì sao chỉ retry những lệnh idempotent? Retry storm là gì? Jitter giúp gì?
5. Saga thiếu tính cô lập (isolation): người dùng có thể thấy trạng thái trung gian nào? Bạn xử lý ra sao?
