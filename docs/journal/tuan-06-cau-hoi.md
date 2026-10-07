# Câu hỏi phỏng vấn tuần 6: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu. Số liệu lấy từ [ADR-0006](../adr/0006-transactional-outbox-voi-polling-relay.md) và [nhật ký tuần 6](2026-W46.md).

## 1. Vì sao không publish thẳng lên Kafka sau khi commit?

Vì đó là **dual-write**: hai lần ghi vào hai hệ thống không có transaction chung, và tiến trình có thể chết ở giữa.

- **Commit database rồi mới gửi Kafka:** chết sau commit, trước khi gửi. Tiền đã chuyển, không có sự kiện, và không có dấu vết nào để biết mà gửi lại.
- **Gửi Kafka rồi mới commit:** commit lỗi (vi phạm ràng buộc, hết `lock_timeout`). Consumer đã nhận sự kiện của một giao dịch không tồn tại.
- Cả hai cách đều buộc API vào Kafka: Kafka chậm hay sập thì request chuyển tiền chậm hay lỗi theo.

Outbox đổi hai lần ghi vào hai hệ thống thành **một** transaction trong một hệ thống: dòng sự kiện nằm cùng transaction với bút toán. Việc đưa lên Kafka tách ra thành một bước sau, lặp lại được, do relay làm.

Trong dự án: `OutboxWriter.append` chạy với `Propagation.MANDATORY`, gọi ngoài transaction là lỗi ngay. Đo thật: dừng Kafka rồi chuyển 30 lần, cả 30 trả 201 trong 1 giây, 30 sự kiện nằm chờ. Bật Kafka, outbox xả hết sau 3 giây.

## 2. Relay phát trùng thì xử lý thế nào? Vì sao Kafka exactly-once không giải quyết được?

Relay gửi lô lên Kafka rồi mới `UPDATE ... SET published_at`. Chết giữa hai bước đó thì lần sau gửi lại cả lô. Không tránh được: lại là hai hệ thống. Thiết kế chọn **mất thì không, trùng thì có**.

Xử lý ở **consumer**: mỗi sự kiện có `eventId` (chính là `id` của dòng outbox, nên gửi lại bao nhiêu lần vẫn là một giá trị). Consumer làm trong một transaction:

```sql
INSERT INTO processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT (event_id) DO NOTHING;
-- 1 dòng: lần đầu thấy, ghi thông báo. 0 dòng: bản trùng, bỏ qua và tăng notification.duplicates.
```

rồi mới commit offset. Dấu "đã xử lý" và hiệu ứng cùng commit, nên chết ở đâu cũng không tạo hai thông báo.

**Kafka exactly-once** (idempotent producer cộng transaction) bảo đảm trong phạm vi Kafka: producer retry không ghi trùng, và mô hình *đọc topic A, ghi topic B, commit offset* là nguyên tử. Nó không biết gì về PostgreSQL. Ở đây hai đầu đều là database:

- Phía relay: "đã gửi Kafka" và "đã đánh dấu trong PostgreSQL" không thể cùng commit.
- Phía consumer: "đã ghi thông báo vào PostgreSQL" và "đã commit offset" cũng không.

Idempotent producer vẫn được bật (`enable.idempotence=true`), nhưng nó chỉ chặn bản trùng do **producer tự retry**, không chặn bản trùng do **relay gửi lại**.

Số đo: `kill -9` `ledger-app` giữa lúc xả 3.000 sự kiện. Kết quả 3.050 giao dịch, 3.072 message trên topic (22 bản trùng), 3.050 thông báo, `notification.duplicates` = 22.

## 3. Thứ tự sự kiện được đảm bảo tới đâu? Khi nào thứ tự bị đảo?

**Được hứa:** các sự kiện của **cùng một aggregate** tới consumer theo thứ tự chúng được ghi. Cách làm: `aggregate_id` là key của record nên chúng nằm trên một partition, relay lấy lô theo `ORDER BY created_at, id`, và gửi **lần lượt**, chờ ack rồi mới gửi cái kế.

**Không được hứa:** thứ tự giữa các aggregate khác nhau. Chúng có thể ở các partition khác nhau.

Thứ tự bị đảo khi:

- **Chạy nhiều relay.** `SKIP LOCKED` cho mỗi relay một lô khác nhau. Hai sự kiện đang cùng tồn đọng của một aggregate có thể rơi vào hai lô, và lô sau có thể được gửi trước. Hiện mỗi giao dịch chỉ có một sự kiện nên chưa xảy ra.
- **Relay hết hạn chờ nhưng record vẫn tới.** Relay chờ ack tối đa 5 giây rồi rollback. Producer có thể vẫn giao record đó sau, khi relay đã gửi lại nó và gửi cả cái kế. Consumer thấy `e1, e2, e1`: bản `e1` thứ hai bị khử trùng nên hiệu ứng vẫn đúng thứ tự.
- **Thứ tự lấy lô là lúc ghi, không phải lúc commit.** Giữa hai aggregate, sự kiện ghi trước mà commit sau có thể được phát sau. Trong một aggregate thì không sao, vì các thay đổi của nó xếp hàng qua khóa dòng. Chỗ này dự án từng sai: `created_at` ban đầu là `now()`, tức lúc transaction **bắt đầu**. Transaction bắt đầu trước nhưng lấy được khóa sau sẽ ghi sự kiện sau mà lại được xếp trước. Một test dựng đúng tình huống đó đã đỏ, và cột được đổi sang `clock_timestamp()`.

Một điều dễ nhầm: gửi cả lô rồi chờ mọi ack thì nhanh hơn, nhưng nếu record giữa lô bị từ chối hẳn mà record sau nó đã vào Kafka thì lần gửi lại làm đảo thứ tự. Vì vậy relay gửi lần lượt.

## 4. Debezium tốt hơn polling ở đâu? Vì sao bạn không dùng?

Debezium đọc WAL của PostgreSQL và đẩy thay đổi của bảng outbox lên Kafka.

Tốt hơn ở:

- **Độ trễ:** gần như ngay, không phải chờ chu kỳ poll.
- **Không có truy vấn poll**, nên không tốn tải khi rảnh.
- **Đúng thứ tự commit**, vì WAL ghi theo thứ tự commit. Polling theo `created_at` thì không.
- Không cần cột `published_at` và câu `UPDATE` đánh dấu.

Không dùng vì:

- Thêm **Kafka Connect và Debezium** phải vận hành, trên một máy 7 GB đã chạy PostgreSQL, Kafka và ba ứng dụng.
- Cần **replication slot**. Connector dừng mà slot còn thì PostgreSQL giữ WAL lại và đĩa đầy dần.
- **Khó test hơn nhiều.** Polling relay là code trong ứng dụng: `RelayCrashDuplicateIT` chèn lỗi đúng vào giữa "đã gửi" và "đã đánh dấu".
- Thứ dự án cần là không mất sự kiện, và polling đã đạt. Độ trễ 200 ms không phải vấn đề cho thông báo.

Debezium **không** bỏ được việc khử trùng: nó cũng là at-least-once.

Phần phải nói thật: đây là lập luận, dự án chưa dựng Debezium để so. Số đo có được là của polling: khoảng 1.600 sự kiện mỗi giây cho một relay trên máy dev.

## 5. `SKIP LOCKED` hoạt động thế nào? Còn dùng được ở đâu khác (job queue)?

`SELECT ... FOR UPDATE` khóa các dòng nó trả về. Gặp dòng đang bị transaction khác khóa, mặc định nó **chờ**. Với `SKIP LOCKED` nó **bỏ qua** dòng đó và lấy dòng kế tiếp thỏa điều kiện.

```sql
SELECT ... FROM outbox_events WHERE published_at IS NULL
ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED;
```

Hai relay chạy câu này cùng lúc: relay A khóa 100 dòng đầu, relay B không chờ A mà nhận 100 dòng tiếp theo. Không ai làm việc của ai, không cần bầu leader, không cần bảng phân công. Relay chết thì transaction của nó rollback, khóa nhả, và dòng lại hiện ra cho relay khác.

Trong dự án, `OutboxEventRepositoryIT` kiểm đúng điều đó: một transaction giữ 2 trong 3 dòng, transaction thứ hai nhận về đúng dòng còn lại mà không chờ. Thay `SKIP LOCKED` bằng `FOR UPDATE` thường thì test đó hết hạn chờ.

Cái giá: kết quả **không còn nhất quán** theo nghĩa thông thường (bạn không thấy những dòng đang bị khóa), nên chỉ dùng cho việc kiểu hàng đợi, không dùng cho báo cáo.

Dùng được ở đâu khác:

- **Job queue trong database:** bảng `jobs`, nhiều worker cùng `SELECT ... WHERE status = 'PENDING' ... FOR UPDATE SKIP LOCKED LIMIT 1`.
- **Worker của saga** (tuần 9): lấy các lệnh nạp tiền tới hạn retry.
- **Job dọn dẹp chạy trên nhiều instance:** job xóa idempotency key hết hạn của tuần 5 dùng đúng cách này.
- Cấp phát tài nguyên từ một pool (số vé, mã giảm giá) mà các request không chờ nhau.

Một lưu ý: khóa giữ tới hết transaction. Relay giữ transaction trong lúc chờ Kafka, nên có hạn 5 giây cho mỗi lần gửi.
