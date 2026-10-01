# Ghi chú: PostgreSQL docs: Explicit Locking và Transaction Isolation

- **Nguồn:** https://www.postgresql.org/docs/18/explicit-locking.html và https://www.postgresql.org/docs/18/transaction-iso.html
- **Đọc để hiểu:** `FOR UPDATE`, `SKIP LOCKED`, READ COMMITTED
- **Liên quan trong Ledgerly:** [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời), [spike-locking](spike-locking.md), ADR-0004
- **Ngày đọc:** ✍️

> Tối đa 1 trang, viết bằng lời của bạn sau khi đọc. Giới hạn cứng cho cả tuần là 4 giờ đọc ([tuần 1](../weeks/tuan-01.md#rủi-ro-và-phương-án)).

## Ý chính

✍️

## Áp dụng vào Ledgerly

✍️

## Tự kiểm tra (trả lời không nhìn tài liệu)

- `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE` khác nhau thế nào? Khóa nào được lấy khi INSERT một dòng có foreign key?
- Ở READ COMMITTED, một câu `UPDATE` bị chặn rồi được chạy tiếp sẽ đọc lại dòng thế nào?
- `SKIP LOCKED` hợp với việc gì và không hợp với việc gì?

## Còn chưa hiểu

✍️
