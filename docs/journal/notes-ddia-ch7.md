# Ghi chú: Martin Kleppmann, Designing Data-Intensive Applications, chương 7

- **Nguồn:** sách, chương *Transactions* (tùy chọn)
- **Đọc để hiểu:** Lost update, write skew
- **Liên quan trong Ledgerly:** [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời), ADR-0004
- **Ngày đọc:** 2026-10-01 (AI ghi chú, xem [ai-usage](../ai-usage.md))

> **Lưu ý:** không có bản sách trong phiên làm việc. Ghi chú này viết từ kiến thức chung về chương 7, **chưa đối chiếu từng trang**.

## Ý chính

- ACID là các khái niệm có nghĩa mơ hồ hơn người ta tưởng. "I" (isolation) trong thực tế hiếm khi là serializable.
- **Các mức isolation yếu:** *read committed* chặn dirty read và dirty write. *Snapshot isolation* (thường gọi là repeatable read, cài bằng MVCC) cho mỗi transaction một ảnh chụp nhất quán.
- **Lost update:** hai transaction cùng đọc, sửa rồi ghi lại một giá trị. Ghi sau đè ghi trước. Cách chống: phép ghi nguyên tử (`UPDATE ... SET x = x + 1`), khóa tường minh (`SELECT ... FOR UPDATE`), để DB tự phát hiện lost update, hoặc compare-and-set (`UPDATE ... WHERE x = giá_trị_cũ`).
- **Write skew:** hai transaction đọc cùng một tập dữ liệu, mỗi bên ghi vào một dòng **khác nhau**, và cùng nhau phá một ràng buộc (ví dụ hai bác sĩ cùng xin nghỉ trực, cuối cùng không ai trực). Snapshot isolation không chặn được. *Phantom* là khi phép ghi làm thay đổi kết quả của một truy vấn tìm kiếm ở transaction khác.
- **Serializable** có ba cách cài đặt: chạy tuần tự thật sự, khóa hai pha (2PL), và Serializable Snapshot Isolation (SSI, lạc quan, phát hiện xung đột lúc commit).

## Áp dụng vào Ledgerly

- Số dư tránh lost update bằng **khóa tường minh** trên dòng account. Kiểm tra số dư và ghi diễn ra trong lúc giữ khóa.
- Chuyển trạng thái saga và giành lại key idempotency dùng **compare-and-set** (`WHERE status = 'PENDING'`, `WHERE locked_until < now()`).
- Write skew có thể xuất hiện nếu một ràng buộc trải trên **nhiều** dòng mà ta không khóa hết. Ví dụ tương lai: hạn mức chuyển tiền theo ngày tính trên tổng các giao dịch. Chặn bằng cách khóa dòng account (một điểm chung để tuần tự hóa) hoặc dùng SERIALIZABLE cho riêng luồng đó.

## Tự kiểm tra

- **Lost update và ba cách chống, Ledgerly dùng cách nào?** Như trên. Ledgerly dùng khóa tường minh cho số dư và compare-and-set cho máy trạng thái.
- **Ledgerly có chỗ nào bị write skew không?** Luồng chuyển tiền hiện tại thì không, vì ràng buộc "số dư không âm" nằm trên **một** dòng đã khóa và còn có `CHECK` ở DB. Rủi ro chỉ xuất hiện khi thêm ràng buộc trên nhiều dòng như hạn mức.
- **Snapshot isolation khác serializable ở đâu?** Snapshot isolation chặn được các bất thường khi đọc nhưng vẫn cho write skew và phantom. Serializable chặn mọi bất thường, đổi lại phải chấp nhận chờ khóa (2PL) hoặc lỗi phải retry (SSI).

## Còn chưa rõ

- Đối chiếu ví dụ cụ thể trong sách về "materializing conflicts" (tạo dòng giả để có cái mà khóa). Có thể hữu ích cho hold/semantic lock (mục *Could* số 11).
