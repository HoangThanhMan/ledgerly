# Tuần 1: Thiết kế và nghiên cứu

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 05/10 – 11/10/2026 | 1: Nền móng | **M1**: Thiết kế chốt | 13–14 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

1. Hiểu sâu bài toán sổ cái kép đến mức **tự giải thích được** từng quyết định trong [01-kien-truc.md](../01-kien-truc.md).
2. Đưa repo lên GitHub với quy trình làm việc chuyên nghiệp: board, milestone, label, branch protection.
3. Tự tay chạy luồng chuyển tiền bằng SQL thuần **trước khi** viết Java.

## Đầu vào

- Khung dự án từ [tuần 0](tuan-00.md).
- Tài liệu nghiên cứu trong [research/](../research/).

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W01-01 | Đọc tài liệu tham chiếu (danh sách bên dưới), ghi chú 1 trang cho mỗi tài liệu | 4 | `docs/journal/notes-*.md` |
| W01-02 | Review [01-kien-truc.md](../01-kien-truc.md): mô hình dữ liệu, luồng 6.1 và 6.2. Sửa chỗ nào chưa hiểu hoặc chưa đồng ý | 2 | PR `docs: review architecture` |
| W01-03 | Viết **ADR-0003**: biểu diễn tiền (BIGINT đơn vị nhỏ nhất + currency) và quy ước dấu của entry | 1 | `docs/adr/0003-bieu-dien-tien-te.md` |
| W01-04 | Review ADR-0001, ADR-0002. Đồng ý thì giữ *Accepted*, không thì sửa | 0.5 | |
| W01-05 | Tạo repo GitHub `ledgerly`, push code, bảo vệ nhánh `main` (bắt buộc CI xanh, cấm force-push) | 1 | Link repo |
| W01-06 | Tạo GitHub Projects board, milestone M1–M5, label, issue cho task tuần 2–8 | 2 | Board có backlog |
| W01-07 | Thêm mẫu issue (`task`, `bug`), tạo `docs/ai-usage.md` và thư mục `docs/journal/` | 1 | |
| W01-08 | **Spike SQL**: mở 2 phiên `psql`, chạy tay `BEGIN; SELECT ... FOR UPDATE; ...` để thấy khóa chờ nhau và deadlock khi khóa sai thứ tự | 2 | `docs/journal/spike-locking.md` |
| W01-09 | Viết nhật ký tuần | 0.5 | `docs/journal/2026-W41.md` |

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

- [ ] ADR-0001, 0002, 0003 ở trạng thái *Accepted*
- [ ] Repo public trên GitHub, CI xanh, `main` đã được bảo vệ
- [ ] Board có tối thiểu 30 issue cho tuần 2–8, gắn milestone và label
- [ ] Spike SQL có ghi chép kèm ảnh chụp hoặc log deadlock
- [ ] Đã trả lời thành tiếng 5 câu hỏi bên dưới mà không cần nhìn tài liệu

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
