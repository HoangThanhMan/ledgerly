# Ghi chú: PostgreSQL docs: Explicit Locking và Transaction Isolation

- **Nguồn:** https://www.postgresql.org/docs/18/explicit-locking.html và https://www.postgresql.org/docs/18/transaction-iso.html
- **Đọc để hiểu:** `FOR UPDATE`, `SKIP LOCKED`, READ COMMITTED
- **Liên quan trong Ledgerly:** [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời), [spike-locking](spike-locking.md), ADR-0004
- **Ngày đọc:** 2026-10-01 (AI đọc và ghi chú, xem [ai-usage](../ai-usage.md))

## Ý chính

**Khóa dòng có bốn mức** (mạnh dần): `FOR KEY SHARE` < `FOR SHARE` < `FOR NO KEY UPDATE` < `FOR UPDATE`. `UPDATE` thường lấy `FOR NO KEY UPDATE`. `DELETE`, hoặc `UPDATE` đổi cột thuộc unique index, thì lấy `FOR UPDATE`. Bảng xung đột:

| | KEY SHARE | SHARE | NO KEY UPDATE | UPDATE |
|---|:-:|:-:|:-:|:-:|
| **KEY SHARE** | | | | ✗ |
| **SHARE** | | | ✗ | ✗ |
| **NO KEY UPDATE** | | ✗ | ✗ | ✗ |
| **UPDATE** | ✗ | ✗ | ✗ | ✗ |

PostgreSQL **không giữ thông tin dòng bị khóa trong bộ nhớ**, nên số dòng khóa được không giới hạn. Đổi lại, khóa một dòng có thể gây ghi đĩa.

**Deadlock:** PostgreSQL tự phát hiện và hủy một trong các transaction. Lời khuyên của tài liệu: mọi ứng dụng nên **khóa nhiều đối tượng theo một thứ tự nhất quán**, và lần khóa đầu tiên trên một đối tượng nên là **mức mạnh nhất** sẽ cần dùng.

**Isolation** (cách PostgreSQL cài đặt): READ UNCOMMITTED chạy như READ COMMITTED. REPEATABLE READ không có phantom read. Chỉ SERIALIZABLE chặn được serialization anomaly.

- **READ COMMITTED:** mỗi câu lệnh thấy dữ liệu đã commit **trước khi câu đó bắt đầu**. Khi `UPDATE` / `DELETE` / `SELECT FOR UPDATE` gặp một dòng đang bị transaction khác sửa, nó **chờ**. Nếu bên kia commit, nó **chạy lại điều kiện `WHERE` trên phiên bản mới** của dòng, rồi làm tiếp nếu vẫn khớp.
- **REPEATABLE READ:** nếu dòng đã bị transaction khác sửa và commit, thì báo `could not serialize access due to concurrent update`. Ứng dụng phải chạy lại **cả transaction**.
- **SERIALIZABLE (SSI):** giống REPEATABLE READ cộng theo dõi phụ thuộc đọc/ghi. Predicate lock **không chặn** nên không gây deadlock, nhưng có thể báo lỗi `40001` bất cứ lúc nào, nên phải có cơ chế retry chung.

## Áp dụng vào Ledgerly

- Luồng chuyển tiền ở READ COMMITTED: khóa account trước bằng một câu `ORDER BY id`, rồi mới kiểm tra số dư. Vì đã giữ khóa, câu đọc số dư sau đó thấy giá trị mới nhất, không có lost update.
- **Đáng cân nhắc ở W04-01:** dùng `FOR NO KEY UPDATE` thay cho `FOR UPDATE`. Ta không đổi khóa chính, và `FOR UPDATE` còn chặn cả `FOR KEY SHARE` mà các câu `INSERT` có foreign key tới `accounts` cần lấy. Mức khóa vẫn đủ mạnh cho câu `UPDATE balance` theo sau (đúng lời khuyên "khóa mức mạnh nhất sẽ cần ngay từ đầu").
- Câu giành lại key idempotency (`UPDATE ... WHERE status = 'IN_PROGRESS' AND locked_until < now()`) dựa đúng vào việc READ COMMITTED chạy lại `WHERE` sau khi chờ.

## Tự kiểm tra

- **Bốn mức khóa khác nhau thế nào? `INSERT` có FK lấy khóa gì?** Như bảng trên. `INSERT` vào bảng con lấy `FOR KEY SHARE` trên dòng cha được tham chiếu, chỉ xung đột với `FOR UPDATE`.
- **Câu `UPDATE` bị chặn rồi chạy tiếp đọc lại dòng thế nào?** Chờ bên kia kết thúc. Nếu bên kia commit thì chạy lại `WHERE` trên phiên bản mới và dùng phiên bản đó. Nếu không còn khớp thì bỏ qua dòng.
- **`SKIP LOCKED` hợp và không hợp với việc gì?** Hợp với hàng đợi công việc nhiều worker (outbox relay, saga worker): mỗi worker lấy các dòng chưa ai giữ. Không hợp khi cần nhìn thấy dữ liệu nhất quán (báo cáo, kiểm tra số dư), vì nó cố ý bỏ qua dòng.

## Còn chưa rõ

- Mức độ "khóa dòng gây ghi đĩa" ảnh hưởng thế nào tới hot account? Đo ở tuần 11.
