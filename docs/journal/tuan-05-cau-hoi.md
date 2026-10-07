# Câu hỏi phỏng vấn tuần 5: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu. Số liệu và lập luận lấy từ [ADR-0005](../adr/0005-idempotency-key-hai-pha-trong-postgresql.md).

## 1. Key lưu ở đâu, TTL bao lâu, vì sao?

Trong bảng `idempotency_keys` của **chính database PostgreSQL** chứa sổ cái. Mỗi dòng có key, hash của request, trạng thái (`IN_PROGRESS` hoặc `COMPLETED`), `lease_token`, response đã lưu (status, body, id của thứ được tạo), `locked_until` và `expires_at`.

Lý do chính: response được lưu **trong cùng transaction** với bút toán. Hoặc tiền đã chuyển *và* key có kết quả, hoặc không có gì. Nếu lưu ở nơi khác thì giữa hai lần ghi luôn có một khe, và chết trong khe đó sẽ cho ra đúng lỗi mà idempotency phải chặn.

TTL **24 giờ**, giống Stripe. Đủ dài để phủ mọi vòng retry hợp lý của client (retry có backoff thường hết trong vài phút, job chạy lại trong vài giờ), đủ ngắn để bảng chỉ giữ khoảng một ngày request. Đây là thời gian **tối thiểu**: job dọn chạy 10 phút một lần, xóa theo lô 1000, và key vẫn replay cho tới khi bị xóa.

Điểm nên nói thêm: key hiện là toàn cục vì chưa có xác thực. Có xác thực thì phải scope theo người gọi.

## 2. Hai request cùng key đến cùng lúc thì chuyện gì xảy ra, chính xác đến từng câu SQL?

Cả hai chạy Tx1:

```sql
INSERT INTO idempotency_keys (idem_key, request_hash, status, lease_token, locked_until, expires_at)
VALUES (:key, :hash, 'IN_PROGRESS', :token, now() + 30s, now() + 24h)
ON CONFLICT (idem_key) DO NOTHING;
```

Unique index trên `idem_key` chỉ cho **một** câu `INSERT` thắng. Request thua (nếu request thắng chưa commit Tx1 thì nó chờ một chút ở index) nhận về 0 dòng, rồi đọc dòng hiện có:

```sql
SELECT ... FROM idempotency_keys WHERE idem_key = :key;
```

- Hash khác: `422 idempotency-key-reused`.
- Có response: trả response đó, kèm `Idempotent-Replayed: true`.
- Đang `IN_PROGRESS`: thử giành lại, và chỉ được khi lease đã hết:

```sql
UPDATE idempotency_keys SET lease_token = :token, locked_until = now() + 30s
WHERE idem_key = :key AND status = 'IN_PROGRESS' AND locked_until <= now();
```

Request đầu vừa claim xong nên lease còn 30 giây: 0 dòng, request thua nhận `409 idempotency-in-progress` kèm `Retry-After: 1`. Nó **không** đứng chờ trong database, nên không giữ connection.

Request thắng chạy Tx2: khóa account, ghi bút toán, rồi

```sql
UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ..., response_body = ..., resource_id = ...
WHERE idem_key = :key AND lease_token = :token AND status = 'IN_PROGRESS';
```

1 dòng thì commit. Request thua gửi lại sau 1 giây và nhận replay.

Số liệu: `IdempotencyConcurrencyIT` cho 50 client cùng key cùng lúc. Kết quả 1 giao dịch, 50 câu trả lời giống nhau từng byte, 49 có `Idempotent-Replayed`.

## 3. Server crash khi key đang `IN_PROGRESS` thì sao?

Phải hỏi lại: crash ở đâu?

- **Trước khi Tx1 commit:** không có dòng nào. Lần retry là một request mới.
- **Sau Tx1, trước hoặc trong Tx2:** Tx2 chưa commit nên database rollback nó. Chưa có bút toán nào. Key kẹt ở `IN_PROGRESS` với `locked_until` trong tương lai. Trong 30 giây đó retry nhận 409. Sau đó lần retry kế giành lại key bằng câu `UPDATE ... AND locked_until <= now()`, nhận token mới và chạy lại action từ đầu.
- **Sau khi Tx2 commit, trước khi response tới client:** tiền đã chuyển và response đã lưu, cùng một commit. Retry nhận replay.

Không có trường hợp "tiền đã chuyển mà key chưa xong", vì hai việc đó là một transaction.

Trường hợp khó hơn crash là **chậm**: request đầu (A) không chết, chỉ đứng hơn 30 giây (chờ khóa, GC, mạng tới database). Request thứ hai (B) giành key. Khi A tỉnh lại, câu `UPDATE ... COMPLETED` của nó có điều kiện `lease_token = :token AND status = 'IN_PROGRESS'`. Token đã bị thay nên A nhận 0 dòng, ném exception và rollback cả bút toán của mình.

Phải nói cho đúng từng điều kiện làm gì, vì dự án đã đo (ADR-0005, B4):

- **Không có điều kiện nào:** A và B đều commit, hai giao dịch, ví nguồn bị trừ hai lần.
- **Chỉ `status = 'IN_PROGRESS'`:** vẫn chỉ một giao dịch. Bên `UPDATE` sau chờ khóa dòng, đọc lại thấy `COMPLETED`, nhận 0 dòng và rollback. Nhưng bên thắng là A, request đã mất lease.
- **Thêm `lease_token` (fencing token):** bên thắng luôn là request đang giữ lease.

Tức là thứ chặn giao dịch thứ hai là compare-and-set trên `status` nằm trong cùng transaction với bút toán. Token làm cho việc hoàn tất gắn với đúng một lần claim, và cho phép thả key an toàn (không thả nhầm lease của request khác).

Nếu request lỗi mà tiến trình còn sống (exception, hết `lock_timeout`), nó tự thả key (`locked_until = now()`) để client retry ngay, không phải chờ hết lease.

## 4. Vì sao không lưu key trong Redis?

Vì **dual write**. Tiền nằm ở PostgreSQL. Nếu key nằm ở Redis thì có hai lần ghi vào hai hệ thống, không có transaction chung:

- Ghi PostgreSQL trước, Redis sau: chết ở giữa thì tiền đã chuyển, Redis chưa biết. Retry chuyển lần hai.
- Ghi Redis trước, PostgreSQL sau: chết ở giữa thì Redis báo "đã xử lý" cho một giao dịch chưa hề xảy ra.

Vá được bằng cách đánh dấu "đang xử lý" ở Redis rồi tra database khi nghi ngờ. Nhưng lúc đó nguồn sự thật vẫn là database, và Redis chỉ còn là cache phía trước.

Ngoài ra: thêm một hệ thống phải vận hành, và độ bền của Redis phụ thuộc cấu hình lưu trữ và failover.

Redis có lợi thật: nhanh, TTL tự dọn, giảm tải ghi cho database chính. Nếu đo thấy bảng key là nút cổ chai thì hướng hợp lý là đặt Redis **trước** bảng để trả replay nhanh, không phải thay bảng.

Phần phải nói thật: so sánh này là lập luận, dự án **chưa đo** chi phí của phương án PostgreSQL. Mỗi request mới tốn thêm một `INSERT`, một `UPDATE` và một transaction.

## 5. Idempotency ở API và idempotency ở consumer Kafka khác nhau thế nào?

Cùng mục tiêu (xử lý lặp không tạo hiệu ứng lặp), khác gần hết phần còn lại:

| | API (`Idempotency-Key`) | Consumer Kafka |
|---|---|---|
| Ai sinh khóa | **Client**, cho mỗi thao tác | **Producer**: `eventId` có sẵn trong sự kiện |
| Vì sao có bản trùng | Client retry vì không biết kết quả | Kafka giao *at-least-once*: relay gửi lại sau khi chết, consumer rebalance trước khi commit offset |
| Người gọi cần gì | **Response** giống lần đầu | Không cần gì, chỉ cần không làm lại |
| Phải lưu | Key, hash, trạng thái, response | Chỉ cần `eventId` đã xử lý |
| Bản trùng đến khi bản đầu đang chạy | Có, thường xuyên. Cần `IN_PROGRESS`, lease, 409 | Hiếm: một partition chỉ có một consumer trong group đọc tại một thời điểm |
| Phát hiện "cùng khóa nhưng nội dung khác" | Có (`request_hash`, trả 422) | Không cần: sự kiện bất biến |
| Cài đặt | Hai pha, fencing token | `INSERT ... ON CONFLICT DO NOTHING` vào bảng `processed_events` trong cùng transaction với hiệu ứng |

Điểm chung quan trọng nhất: **dấu "đã xử lý" phải commit cùng transaction với hiệu ứng**. Ở API là key và bút toán. Ở consumer là `eventId` và việc nó làm. Tách hai lần ghi ra là quay lại bài toán dual write.

Phần consumer là thiết kế của tuần 6, chưa cài đặt.
