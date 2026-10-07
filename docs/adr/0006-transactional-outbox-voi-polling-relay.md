# ADR-0006: Transactional outbox với polling relay

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Tuần:** 6
- **Liên quan:** [01-kien-truc §6.2](../01-kien-truc.md#62-outbox-relay-và-consumer-idempotent-tuần-6), [§9](../01-kien-truc.md#9-sự-kiện-và-kafka), ADR-0002, ADR-0005, issue #45–#53, [ghi chú về transactional outbox](../journal/notes-transactional-outbox.md)

> ADR này do AI soạn theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Số liệu trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-07 trên máy dev (7 GB RAM, PostgreSQL 18 và Kafka 4.3.1 trong Docker). Phần so sánh với Debezium, với Kafka transaction và với cách gửi song song là **lập luận**, chưa đo.

## Bối cảnh

Khi một lần chuyển tiền commit, các hệ thống khác cần biết (trước mắt là `notification-consumer`). Có hai thứ phải ghi: bút toán vào PostgreSQL và sự kiện lên Kafka. Hai hệ thống đó không có transaction chung.

Các lực tác động:

- **Không được mất sự kiện.** Tiền đã chuyển mà không có sự kiện thì không ai biết để gửi lại.
- **Không được có sự kiện ma.** Sự kiện cho một giao dịch đã rollback là báo sai.
- **API không được phụ thuộc Kafka.** Kafka sập thì chuyển tiền vẫn phải chạy.
- **Tiến trình có thể chết ở bất kỳ dòng nào**, kể cả giữa lúc gửi Kafka và lúc ghi nhận đã gửi.
- **Thứ tự.** Các sự kiện của cùng một aggregate phải tới consumer theo thứ tự xảy ra.
- **Hạ tầng tối thiểu.** Dự án chỉ có PostgreSQL và Kafka. Mỗi thành phần thêm vào phải vận hành được trên một máy 7 GB RAM.

## Các phương án đã cân nhắc

### Câu hỏi 1: Làm sao để bút toán và sự kiện đi cùng nhau

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Dual-write: commit database rồi `kafka.send` | Ít code nhất, độ trễ thấp nhất | Chết giữa hai bước là mất sự kiện, không có gì để phát hiện. Đảo thứ tự (gửi trước, commit sau) thì có sự kiện ma. Kafka sập thì hoặc API lỗi theo, hoặc sự kiện bị bỏ |
| B. Kafka transaction (`transactional.id`) | Nguyên tử **trong Kafka**: nhiều record và offset cùng commit | Không bao phủ PostgreSQL. Vẫn là hai lần commit ở hai hệ thống, tức vẫn là dual-write |
| C. Transactional outbox: ghi sự kiện vào một bảng trong **cùng transaction** với bút toán, một tiến trình khác chuyển lên Kafka | Sự kiện tồn tại khi và chỉ khi giao dịch commit. API không đụng tới Kafka. Kafka sập thì sự kiện nằm chờ | Thêm một bảng, một lần ghi cho mỗi giao dịch, và một relay. Chỉ đạt **at-least-once**: consumer phải khử trùng |

### Câu hỏi 2: Chuyển outbox lên Kafka bằng gì

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Polling: relay định kỳ `SELECT` các dòng chưa phát | Chỉ cần SQL. Chạy trong chính ứng dụng, test được bằng Testcontainers | Độ trễ tới một chu kỳ poll. Truy vấn lặp lại kể cả khi không có gì. Bảng outbox lớn dần |
| Log tailing (Debezium đọc WAL) | Độ trễ thấp, không có truy vấn poll, giữ đúng thứ tự commit | Thêm Kafka Connect và Debezium phải vận hành, cần replication slot (slot bị bỏ quên làm WAL phình ra). Khó dựng trong test hơn nhiều |

### Câu hỏi 3: Nhiều relay và relay chết giữa chừng

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Một relay duy nhất (leader election) | Thứ tự đơn giản | Cần cơ chế bầu chọn, và vẫn phải xử lý lúc chuyển giao |
| Mỗi vòng là một transaction: `FOR UPDATE SKIP LOCKED`, gửi, đánh dấu, commit | Nhiều relay chạy cùng lúc mà không cần phối hợp: mỗi relay khóa một lô khác. Relay chết thì khóa tự nhả và lô được gửi lại | Giữ transaction trong lúc chờ Kafka. Chết sau khi gửi và trước khi commit là gửi trùng. Hai relay có thể phát hai sự kiện của cùng một aggregate sai thứ tự |

### Câu hỏi 4: Gửi một lô thế nào

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Lần lượt: gửi một record, chờ ack, rồi mới gửi record kế | Record lỗi thì chưa record nào sau nó được gửi: thứ tự chặt | Thông lượng bị giới hạn bởi một round trip cho mỗi sự kiện |
| Gửi cả lô rồi chờ mọi ack | Nhanh hơn nhiều lần | Một record giữa lô lỗi hẳn trong khi record sau nó đã vào Kafka: lần gửi lại làm đảo thứ tự |

## Quyết định

Chúng tôi sẽ dùng **transactional outbox với polling relay** (1C, polling ở câu hỏi 2, `SKIP LOCKED` ở câu hỏi 3, gửi lần lượt ở câu hỏi 4).

- **Ghi:** `OutboxWriter.append` chạy với `Propagation.MANDATORY`, nên gọi ngoài transaction là lỗi ngay. `TransferService.transfer` là một transaction chứa bút toán và sự kiện `TransferCompleted`. Qua API thì transaction đó chính là Tx2 của idempotency (ADR-0005): bút toán, sự kiện và response đã lưu cùng commit.
- **Chuyển:** mỗi 200 ms, relay chạy các vòng cho tới khi hết tồn đọng. Một vòng:

```sql
BEGIN;
SELECT ... FROM outbox_events WHERE published_at IS NULL
ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED;
-- gửi từng sự kiện lên Kafka (key = aggregate_id), chờ ack, tối đa 5 giây mỗi sự kiện
UPDATE outbox_events SET published_at = now() WHERE id = ANY (:ids);
COMMIT;
```

- **Lỗi:** vòng rollback, sự kiện vẫn là chưa phát, cột `attempts` tăng (bằng một câu lệnh riêng sau khi rollback). Relay nghỉ 200 ms, gấp đôi sau mỗi lần lỗi liên tiếp, tối đa 30 giây.
- **Producer:** `acks=all`, `enable.idempotence=true`, `linger.ms=0`, và các timeout ngắn (`max.block.ms` 5 giây) để relay không treo khi broker mất.
- **Consumer:** khử trùng theo `eventId` bằng bảng `processed_events`, ghi trong cùng transaction với hiệu ứng, commit offset sau.
- **Thứ tự** chỉ được hứa **theo aggregate**: `aggregate_id` là key của record, nên các sự kiện của một aggregate nằm trên một partition.

Debezium bị loại vì thêm hai thành phần phải vận hành cho một lợi ích (độ trễ dưới 200 ms) mà dự án chưa cần. Kafka transaction bị loại vì nó không giải bài toán: ranh giới cần nguyên tử nằm giữa PostgreSQL và Kafka.

## Hệ quả

- **Tích cực:** sự kiện và giao dịch là một transaction, nên không có sự kiện mất hay sự kiện ma do ứng dụng chết (B1, B2). Kafka sập không ảnh hưởng API: sự kiện nằm chờ và tự được phát khi Kafka trở lại (B3). Relay chết giữa chừng không làm mất gì (B4). Không thêm hạ tầng.
- **Tiêu cực / đánh đổi:**
  - **At-least-once, không phải exactly-once.** Mọi consumer phải khử trùng. Trong lần chạy B4, 22 trên 3.072 message là bản trùng.
  - **Độ trễ** tới 200 ms cộng thời gian gửi. Khi relay đang nghỉ sau lỗi thì tới 30 giây.
  - **Relay giữ một transaction và một connection trong lúc chờ Kafka.** Chấp nhận được vì chỉ có một luồng nền làm vậy, khóa chỉ nằm trên dòng outbox, và mỗi lần gửi có hạn 5 giây.
  - **Thông lượng** của một relay khoảng 1.600 sự kiện mỗi giây trên máy dev (B5). Chưa đo dưới tải có ghi đồng thời: việc đó của tuần 7.
- **Thứ tự có ba giới hạn cần biết:**
  - Chỉ theo aggregate. Giữa các aggregate không có thứ tự nào được hứa.
  - Thứ tự lấy lô là `created_at`, tức lúc sự kiện **được ghi** (`clock_timestamp()`), không phải lúc transaction commit. Một transaction ghi sớm mà commit muộn sẽ hiện ra sau khi những sự kiện trẻ hơn đã được phát. Vì vậy relay tìm việc bằng `published_at IS NULL` chứ không nhớ "đã đọc tới đâu". Trong một aggregate thì thứ tự ghi là thứ tự đúng, miễn là sự kiện được ghi khi transaction đang giữ khóa của aggregate đó (B8).
  - Với **nhiều relay**, hai sự kiện của cùng một aggregate đang cùng tồn đọng có thể rơi vào hai lô của hai relay và được phát sai thứ tự. Hiện mỗi giao dịch chỉ có một sự kiện nên chưa xảy ra. Khi có aggregate nhiều sự kiện (nạp, rút ở tuần 9) mà chạy nhiều relay thì phải xử lý.
- **Sự kiện hỏng chặn hàng.** Một sự kiện mà Kafka luôn từ chối (ví dụ quá lớn) làm lô chứa nó lỗi mãi, và mọi sự kiện sau nó trong lô cũng không đi. Cột `attempts` cho thấy điều đó, nhưng chưa có cơ chế gạt sự kiện đó sang một bên.
- **Hai bảng lớn mãi.** `outbox_events` giữ cả sự kiện đã phát, `processed_events` giữ mọi `eventId`. Chưa có job dọn. Partial index giữ cho truy vấn của relay không chậm theo, nhưng dung lượng thì vẫn tăng.
- **Nạp tiền nội bộ không phát sự kiện.** I6 vì vậy được phát biểu cho giao dịch chuyển tiền.
- **Cần theo dõi:** `ledgerly.outbox.pending` và `ledgerly.outbox.oldest.age` (độ trễ của relay), `notification.duplicates`, và số sự kiện có `attempts` cao.

## Bằng chứng

**B1. Sự kiện đi cùng giao dịch** (`OutboxAtomicityIT`, 8 ca). Giao dịch commit thì có đúng 1 dòng outbox. Rollback sau khi cả bút toán và sự kiện đã ghi thì không còn cả hai. Bị từ chối vì thiếu tiền thì không có sự kiện. Gửi lại cùng `Idempotency-Key` không thêm sự kiện thứ hai. 400 lần chuyển đồng thời trên 20 luồng: tập `aggregate_id` của sự kiện trùng khít với tập giao dịch hoàn tất. Gọi `append` ngoài transaction: `IllegalTransactionStateException`.

**B2. Relay** (`OutboxRelayIT`, 6 ca). 1.000 lần chuyển thành đúng 1.000 message, mỗi `eventId` một lần, `outbox.pending` về 0. Message trên Kafka có đúng tập trường của file mẫu trong `ledger-contracts`, là file mà `EventContractTest` của consumer đọc. Ba sự kiện của một aggregate nằm trên một partition, đúng thứ tự.

**B3. Kafka sập, chạy tay với hai ứng dụng thật** (`java -jar`, PostgreSQL và Kafka trong compose):

| Bước | Kết quả |
|---|---|
| Mọi thứ đang chạy, 20 lần chuyển | 20 × 201, 20 message, 20 thông báo |
| `docker compose stop kafka`, 30 lần chuyển | 30 × 201 trong 1 giây, `outbox.pending` = 30, thông báo vẫn 20 |
| `docker compose start kafka` | `outbox.pending` về 0 sau 3 giây, thông báo lên 50, 0 bản trùng |

**B4. `kill -9` relay giữa lúc đang xả, cùng lần chạy đó.** Dừng Kafka, tạo 3.000 lần chuyển (3.000 × 201 trong 5 giây, tồn đọng 3.000), bật Kafka, rồi `kill -9` `ledger-app` khi database ghi nhận 150 sự kiện đã phát. Lúc đó Kafka đã có 172 message: 22 message thuộc một lô đã gửi mà chưa kịp commit. Khởi động lại:

| Đại lượng | Giá trị |
|---|:-:|
| Giao dịch chuyển tiền trong sổ cái | 3.050 |
| Sự kiện trong outbox, chưa phát | 3.050, 0 |
| Message trên topic | 3.072 (22 bản trùng) |
| Thông báo consumer tạo ra | 3.050 |
| `notification.duplicates` | 22 |
| Dòng vi phạm của `scripts/invariants.sql` và `scripts/invariants-events.sql` | 0 |

Trong test tự động, cùng tình huống được dựng bằng `OutboxFaults` (`RelayCrashDuplicateIT`): hai vòng chết sau khi gửi, mỗi sự kiện có 3 bản trên Kafka, cả ba giống nhau từng byte.

**B5. `linger.ms`.** `OutboxRelayIT` đo thời gian xả 1.000 sự kiện: 6.736 ms với mặc định của Kafka 4 (`linger.ms=5`), 602 ms và 638 ms với `linger.ms=0`. Relay chờ ack từng record nên không bao giờ có record thứ hai để gom, và 5 ms đó cộng vào mỗi sự kiện.

**B6. Consumer** (`ConsumerDedupIT`, 5 ca). Cùng `eventId` gửi 5 lần: 1 thông báo, `notification.duplicates` tăng 4. Message không đọc được không chặn message sau nó. Xử lý rollback thì `eventId` không bị ghi nhớ, lần giao lại được xử lý bình thường.

**B7. Tài liệu.** microservices.io, *Transactional outbox*, đã ghi chú ở [notes-transactional-outbox](../journal/notes-transactional-outbox.md).

**B8. `created_at` phải là lúc ghi.** Bản đầu dùng `DEFAULT now()`, tức lúc transaction bắt đầu. `OutboxEventRepositoryIT.eventsAreOrderedByWhenTheyWereWrittenNotByWhenTheirTransactionBegan` dựng tình huống: transaction T1 bắt đầu, transaction T2 ghi sự kiện và commit, rồi T1 mới ghi sự kiện của nó. Với `now()`, sự kiện của T1 được xếp **trước** dù ghi sau. Hai thay đổi trên cùng một aggregate xếp hàng qua khóa dòng đúng theo kiểu đó, nên thứ tự theo aggregate sẽ sai. Đổi sang `clock_timestamp()` thì test xanh.
