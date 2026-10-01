# Câu hỏi phỏng vấn tuần 2: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu.

## 1. Vì sao không dùng H2 cho test?

Vì H2 **không phải PostgreSQL**, nên test xanh trên H2 không chứng minh được gì về hệ thống thật. Những thứ Ledgerly dựa vào để đúng đều là hành vi riêng của PostgreSQL: constraint trigger `DEFERRABLE INITIALLY DEFERRED` viết bằng PL/pgSQL, `uuidv7()`, `FOR UPDATE` / `SKIP LOCKED` cùng cách READ COMMITTED chạy lại `WHERE` sau khi chờ khóa, mã lỗi `40P01` / `55P03`. Chế độ tương thích của H2 chỉ giả lập cú pháp, không giả lập hành vi khóa.

Testcontainers chạy đúng `postgres:18-alpine` như compose. Cái giá là cần Docker và chậm hơn. Dự án bù lại bằng container dùng chung cho cả JVM test (2 context mà chỉ 1 container) và suite `integrationTest` tách riêng, nên `./gradlew test` vẫn dưới 1 giây.

## 2. Constraint trigger `DEFERRABLE INITIALLY DEFERRED` hoạt động thế nào? Vì sao phải deferred?

Trigger thường chạy ngay khi câu lệnh chạy. **Constraint trigger** là loại trigger `AFTER ... FOR EACH ROW` có thể **hoãn** tới lúc `COMMIT`. `INITIALLY DEFERRED` nghĩa là mặc định hoãn, trừ khi transaction gọi `SET CONSTRAINTS ... IMMEDIATE`.

Phải hoãn vì một giao dịch chuyển tiền gồm nhiều câu `INSERT entries`. Sau câu đầu (−100), tổng **tạm thời** khác 0. Nếu kiểm tra ngay thì mọi giao dịch hợp lệ đều bị chặn. Kiểm tra lúc commit thì chỉ trạng thái cuối mới phải cân bằng. Bằng chứng là `SchemaConstraintsIT.unbalancedTransactionIsRejectedAtCommit`: câu `INSERT` một entry +100 chạy được, còn `connection.commit()` ném lỗi `is not balanced` và cả transaction bị rollback.

## 3. Vì sao chặn bất biến ở **cả** code Java **và** database?

Mỗi lớp làm một việc khác nhau:

- **Java (`PostingRules`, tuần 3)** chặn sớm và cho **lỗi nghiệp vụ rõ ràng**: thiếu tiền thì trả `422 insufficient-funds` kèm thông điệp, không chạm DB.
- **Database** là **lớp chặn cuối** cho những gì Java không bao giờ được làm: code có bug, một script hay câu `psql` chạy tay, migration sai, hoặc race condition mà khóa ở Java chưa che hết. Lỗi từ tầng DB nghĩa là **có bug**: trả 500 và cảnh báo, không phải một luồng nghiệp vụ bình thường.

Chỉ có Java thì một bug là đủ ghi sai sổ. Chỉ có DB thì người dùng nhận lỗi khó hiểu, và logic nghiệp vụ bị giấu trong trigger. Đây là phòng thủ nhiều lớp (P3 trong kiến trúc).

## 4. Unit test khác integration test ở đâu trong dự án này?

| | Unit test | Integration test |
|---|---|---|
| Thư mục | `src/test/java` | `src/integrationTest/java` |
| Lệnh | `./gradlew test` | `./gradlew integrationTest` (cả hai chạy trong `check`) |
| Phạm vi | Java thuần: `Money`, `PostingRules` (tuần 3). Không Spring, không DB | Spring context, PostgreSQL và Kafka thật qua Testcontainers |
| Kiểm tra gì | Logic tính toán, quy tắc nghiệp vụ | SQL, ràng buộc DB, khóa, đồng thời, outbox |
| Tốc độ | Mục tiêu dưới 10 giây cho cả suite | Vài chục giây, container dùng chung |

Ranh giới là **có cần hạ tầng thật hay không**, chứ không phải độ lớn của đoạn code được test.
