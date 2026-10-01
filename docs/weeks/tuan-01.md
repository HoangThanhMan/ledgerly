# Tuần 1: Thiết kế và nghiên cứu

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 05/10 – 11/10/2026 | 1: Nền móng | **M1**: Thiết kế chốt | 13–14 giờ | 🟡 Gần xong (01/10/2026): còn merge PR #68–#71, bảo vệ `main`, tạo board, luyện 5 câu hỏi |

## Mục tiêu

1. Hiểu sâu bài toán sổ cái kép đến mức **tự giải thích được** từng quyết định trong [01-kien-truc.md](../01-kien-truc.md).
2. Đưa repo lên GitHub với quy trình làm việc chuyên nghiệp: board, milestone, label, branch protection.
3. Tự tay chạy luồng chuyển tiền bằng SQL thuần **trước khi** viết Java.

## Đầu vào

- Khung dự án từ [tuần 0](tuan-00.md).
- Tài liệu nghiên cứu trong [research/](../research/).

## Công việc

| ID | Việc | Giờ | Đầu ra | Trạng thái |
|---|---|:-:|---|---|
| W01-01 | Đọc tài liệu tham chiếu (danh sách bên dưới), ghi chú 1 trang cho mỗi tài liệu | 4 | `docs/journal/notes-*.md` | ✅ PR #71 |
| W01-02 | Review [01-kien-truc.md](../01-kien-truc.md): mô hình dữ liệu, luồng 6.1 và 6.2. Sửa chỗ nào chưa hiểu hoặc chưa đồng ý | 2 | PR `docs: review architecture` | ✅ PR #68 |
| W01-03 | Viết **ADR-0003**: biểu diễn tiền (BIGINT đơn vị nhỏ nhất + currency) và quy ước dấu của entry | 1 | `docs/adr/0003-bieu-dien-tien-te.md` | ✅ PR #69 |
| W01-04 | Review ADR-0001, ADR-0002. Đồng ý thì giữ *Accepted*, không thì sửa | 0.5 | | ✅ PR #68 |
| W01-05 | Tạo repo GitHub `ledgerly`, push code, bảo vệ nhánh `main` (bắt buộc CI xanh, cấm force-push) | 1 | Link repo | 🟡 Repo đã có, chưa bảo vệ `main` (#5) |
| W01-06 | Tạo GitHub Projects board, milestone M1–M5, label, issue cho task tuần 2–8 | 2 | Board có backlog | 🟡 Có label, milestone, 67 issue. Chưa có board (#6) |
| W01-07 | Thêm mẫu issue (`task`, `bug`), tạo `docs/ai-usage.md` và thư mục `docs/journal/` | 1 | | ✅ PR #71 |
| W01-08 | **Spike SQL**: mở 2 phiên `psql`, chạy tay `BEGIN; SELECT ... FOR UPDATE; ...` để thấy khóa chờ nhau và deadlock khi khóa sai thứ tự | 2 | `docs/journal/spike-locking.md` | ✅ PR #70 |
| W01-09 | Viết nhật ký tuần | 0.5 | `docs/journal/2026-W41.md` | ✅ PR #71 |

## Ghi chú kỹ thuật

### Spike SQL (W01-08)

Mục đích là **tự thấy** deadlock trước khi viết code chống deadlock:

```sql
-- Phiên 1                                   -- Phiên 2
BEGIN;                                       BEGIN;
SELECT * FROM t WHERE id = 1 FOR UPDATE;
                                             SELECT * FROM t WHERE id = 2 FOR UPDATE;
SELECT * FROM t WHERE id = 2 FOR UPDATE;     -- (phiên 1 chờ)
                                             SELECT * FROM t WHERE id = 1 FOR UPDATE;
                                             -- ERROR: deadlock detected (40P01)
```

Lặp lại với cả hai phiên khóa theo thứ tự `id` tăng dần, rồi ghi lại sự khác biệt. Kết quả này sẽ được trích vào ADR-0004.

### Nội dung ADR-0003 cần trả lời

- Vì sao không dùng `double`? Chạy `0.1 + 0.2` trong `jshell` và dán kết quả.
- Vì sao chọn `long` đơn vị nhỏ nhất thay vì `BigDecimal`? Bàn về hiệu năng, mapping với DB và giới hạn tràn số (`Math.addExact`).
- Dấu của entry: dùng số có dấu hay tách hai cột debit/credit? Hệ quả với bất biến "tổng bằng 0".
- Vì sao API trả số tiền dạng **chuỗi**? (JavaScript `Number` chỉ chính xác đến 2^53.)

## Tài liệu đọc

| Tài liệu | Đọc để hiểu |
|---|---|
| Stripe: *Designing robust and predictable APIs with idempotency* | Mô hình `Idempotency-Key` |
| Modern Treasury: *How to Scale a Ledger* (phần I–II) | Mô hình Accounts / Transactions / Entries |
| microservices.io: *Transactional outbox* | Vì sao không dual-write |
| System Design Interview Vol. 2, chương *Payment System* và *Digital Wallet* | Exactly-once, đối soát, saga |
| PostgreSQL docs: *Explicit Locking*, *Transaction Isolation* | `FOR UPDATE`, `SKIP LOCKED`, READ COMMITTED |
| Martin Kleppmann, *Designing Data-Intensive Applications*, chương 7 (tùy chọn) | Lost update, write skew |

## Definition of Done

- [x] ADR-0001, 0002, 0003 ở trạng thái *Accepted* (0003 sau khi merge PR #69)
- [ ] Repo public trên GitHub, CI xanh, `main` đã được bảo vệ (CI xanh; còn public và bảo vệ `main`)
- [ ] Board có tối thiểu 30 issue cho tuần 2–8, gắn milestone và label (đã có 58 issue tuần 2–8; còn tạo board)
- [x] Spike SQL có ghi chép kèm ảnh chụp hoặc log deadlock ([spike-locking](../journal/spike-locking.md))
- [ ] Đã trả lời thành tiếng 5 câu hỏi bên dưới mà không cần nhìn tài liệu ([đáp án tham khảo](../journal/m1-cau-hoi.md))

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Đọc quá lâu, không ra sản phẩm | Giới hạn cứng 4 giờ đọc. Phần chưa đọc chuyển sang tuần sau |
| Muốn đổi thiết kế lớn sau khi đọc | Cho phép, nhưng phải viết thành ADR trong tuần này, sau tuần 1 thì khóa thiết kế |

## Câu hỏi phỏng vấn tự luyện

1. Sổ cái kép là gì? Vì sao tổng các entry của một giao dịch phải bằng 0?
2. Vì sao lưu cả `balance` lẫn `entries`? Nếu chúng lệch nhau thì tin cái nào?
3. Idempotency khác retry thế nào? Exactly-once được tạo ra từ hai thứ gì?
4. Dual-write là gì? Cho một ví dụ mất dữ liệu cụ thể.
5. Vì sao bạn chọn modular monolith trong khi đã từng làm microservice?
