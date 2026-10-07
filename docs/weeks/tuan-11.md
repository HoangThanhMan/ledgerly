# Tuần 11: Đối soát và benchmark so sánh

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 04/01 – 10/01/2027 | 5: Mở rộng | | 13.5 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

1. Job đối soát phát hiện và **tự xử lý** chênh lệch giữa sổ cái và ngân hàng. Rất ít dự án sinh viên có phần này, trong khi đây là nghiệp vụ fintech thật.
2. Trả lời bằng số liệu hai câu hỏi: *chiến lược khóa nào tốt cho hot account?* và *virtual threads có thật sự nhanh hơn không?*

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W11-01 | `reconciliation`: Flyway `V6`, job hằng ngày (`@Scheduled` + chạy tay qua API), so khớp theo `merchantRef` | 3 | |
| W11-02 | Tự xử lý (auto-heal) giao dịch `UNKNOWN` dựa trên sao kê. Phần còn lại ghi `OPEN` | 1 | |
| W11-03 | `GhostChargeReconciliationIT`: `ghostChargeRate = 10%`, sau đối soát không còn `UNKNOWN` | 1.5 | |
| W11-04 | Benchmark hot account: k6 `hot-account.js`, 500 ví cùng chuyển vào 1 ví merchant | 1 | |
| W11-05 | Cài 3 chiến lược sau feature flag `ledgerly.ledger.lock-strategy`: `pessimistic`, `optimistic` (version + retry), `buffered` (ghi qua sub-account rồi gộp định kỳ) | 3 | |
| W11-06 | Benchmark virtual threads và platform threads (Tomcat 200), cùng profile tải, cùng máy | 1.5 | |
| W11-07 | *(Could)* Module `ledger-benchmarks` (JMH) cho `PostingRules` và serialize sự kiện | 1.5 | |
| W11-08 | **ADR-0010**: chiến lược hot account. Nếu kết quả đảo ngược một phần ADR-0004 thì đánh dấu *superseded* | 1 | |
| W11-09 | *(Could)* Dùng LLM phân loại và giải thích các dòng đối soát còn `OPEN`, chấm bằng bộ eval của tuần A2 | 2 | |

## Ghi chú kỹ thuật

### Bảng kết quả cần điền (`docs/benchmarks.md`)

| Chiến lược | Throughput (TPS) | p50 | p95 | p99 | Tỉ lệ lỗi / retry | Bất biến |
|---|--:|--:|--:|--:|--:|:-:|
| Pessimistic (`FOR UPDATE`) | | | | | | |
| Optimistic (version + 3 retry) | | | | | | |
| Buffered sub-accounts (N = 8) | | | | | | |

| Chế độ thread | RPS mục tiêu | p99 | Lỗi | CPU | Hikari pending (max) |
|---|--:|--:|--:|--:|--:|
| Platform (Tomcat 200) | | | | | |
| Virtual + `@ConcurrencyLimit` | | | | | |

**Quy tắc:** mỗi lần chỉ thay đổi **một** biến, trên **cùng một** máy, chạy 3 lần, lấy trung vị.

### Chiến lược `buffered`

Ví merchant được chia thành N sub-account. Mỗi giao dịch ghi vào một sub-account chọn theo hash, nên giảm tranh khóa N lần. Một job định kỳ gộp các sub-account về account chính bằng một ledger transaction (vẫn tổng bằng 0). Đổi lại: số dư "thật" của merchant trễ vài giây. Đây là trade-off **phải ghi rõ** trong ADR.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `ReconciliationIT.matchesAll` | Không có chênh lệch thì 100% `MATCHED` |
| `ReconciliationIT.detectsMissingInBank` | Sổ cái có, bank không có thì sinh item `MISSING_IN_BANK` |
| `GhostChargeReconciliationIT` | Như mô tả |
| `LockStrategyIT` (chạy cho cả 3 chiến lược) | Test concurrency của tuần 4 xanh với mọi chiến lược |

## Definition of Done

- [ ] Hai bảng benchmark đã điền đủ, có link tới kết quả thô
- [ ] Báo cáo đối soát xem được qua API
- [ ] ADR-0010 Accepted (và ADR-0004 *superseded* nếu cần)

## Câu hỏi phỏng vấn tự luyện

1. Đối soát phát hiện chênh lệch bằng cách nào? Ai xử lý phần `OPEN`?
2. Vì sao khóa lạc quan tệ ở hot account? Số liệu của bạn nói gì?
3. Chiến lược `buffered` đánh đổi điều gì? Khi nào **không** được dùng?
4. Virtual threads nhanh hơn bao nhiêu trong benchmark của bạn? Vì sao không nhiều như kỳ vọng (hoặc vì sao nhiều)?
