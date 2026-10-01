# Tuần 6: Transactional outbox, Kafka và consumer idempotent

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 09/11 – 15/11/2026 | 2: Lõi đúng đắn | **M3**: Nhất quán | 13.5 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Mỗi giao dịch đã commit **chắc chắn** sinh ra đúng một sự kiện trên Kafka, kể cả khi Kafka sập hoặc relay bị kill. Bản trùng (nếu có) bị consumer loại bỏ và **đếm được**.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W06-01 | `ledger-contracts`: `EventEnvelope<T>`, record `TransferCompleted`, hằng số tên topic | 1.5 | |
| W06-02 | Flyway `V4__outbox_events.sql`, partial index `WHERE published_at IS NULL` | 0.5 | |
| W06-03 | `OutboxWriter.append(...)` với `Propagation.MANDATORY`, gọi trong transaction chuyển tiền | 1 | |
| W06-04 | `OutboxRelay`: `@Scheduled(fixedDelay)`, lấy theo lô bằng `FOR UPDATE SKIP LOCKED`, gửi, chờ ack, đánh dấu đã phát | 3 | |
| W06-05 | Relay chịu lỗi: Kafka lỗi thì rollback lô, backoff tăng dần, metric `outbox.pending` và `outbox.oldest.age` | 1 | |
| W06-06 | Cấu hình producer `acks=all`, `enable.idempotence=true`. Bean `NewTopic` (3 partition) | 0.5 | |
| W06-07 | `notification-consumer`: Flyway `V1` (`processed_events`, `notifications`), `@KafkaListener` khử trùng trong một transaction | 2.5 | |
| W06-08 | Test: `OutboxAtomicityIT`, `OutboxRelayIT`, `RelayCrashDuplicateIT`, `ConsumerDedupIT` | 2.5 | |
| W06-09 | **ADR-0006**: outbox + polling relay, so với dual-write và Debezium CDC | 1 | |

## Ghi chú kỹ thuật

### Relay

```java
@Scheduled(fixedDelayString = "${ledgerly.outbox.poll-interval:200ms}")
void relayBatch() {
    tx.executeWithoutResult(status -> {
        var batch = repository.lockNextBatch(100);       // FOR UPDATE SKIP LOCKED
        for (var event : batch) {
            kafka.send(toRecord(event)).get(5, SECONDS); // chờ ack, lỗi thì ném ra và rollback
        }
        repository.markPublished(ids(batch));
    });
}
```

- `SKIP LOCKED` cho phép **chạy nhiều instance relay** mà không phát trùng do tranh nhau, chỉ còn trùng do crash.
- Gửi xong mà crash trước `COMMIT` thì lần sau gửi lại. Đó là at-least-once và **có chủ đích**.
- Thứ tự: cùng `aggregate_id` thì cùng partition. Trong một lô, gửi theo thứ tự `created_at`.
- Có property `ledgerly.outbox.relay.enabled`. Cùng một artifact có thể chạy với vai trò "chỉ relay". Đây là điểm mở rộng tốt để nói khi phỏng vấn.

### Consumer khử trùng

```sql
-- trong cùng một transaction DB của consumer
INSERT INTO processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT DO NOTHING;
-- 0 dòng bị ảnh hưởng → đã xử lý → bỏ qua, tăng notification.duplicates
-- 1 dòng → xử lý nghiệp vụ (INSERT notifications), rồi COMMIT, rồi commit offset
```

Commit offset **sau** commit DB. Crash ở giữa thì message được giao lại, và bảng `processed_events` chặn việc xử lý hai lần.

### Giả lập crash relay trong test (W06-08)

Inject lỗi **sau khi gửi Kafka, trước khi commit DB** cho N lô đầu. Khẳng định:

1. Trên Kafka có bản trùng (đếm bằng consumer test đọc thô).
2. Bảng `notifications` có **đúng 1** dòng cho mỗi giao dịch.
3. Metric `notification.duplicates` bằng số bản trùng.

Bài `kill -9` tiến trình thật sẽ làm ở tuần 10 bằng compose.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `OutboxAtomicityIT` | Transaction chuyển tiền rollback thì không có dòng outbox. Commit thì có đúng 1 dòng |
| `OutboxRelayIT` | 1.000 giao dịch dẫn tới 1.000 message trên Kafka, `outbox.pending` về 0 |
| `RelayCrashDuplicateIT` | Như mô tả ở trên |
| `ConsumerDedupIT` | Gửi cùng `eventId` 5 lần thì chỉ 1 notification |
| `EventContractTest` | JSON mẫu (fixture) của producer deserialize được bởi consumer |

## Definition of Done

- [ ] Toàn bộ test xanh
- [ ] Dừng Kafka thủ công (`docker compose stop kafka`): API vẫn trả 201, outbox tồn đọng. Bật lại thì outbox xả hết
- [ ] ADR-0006 Accepted
- [ ] **Mốc M3 đạt**

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Polling 200 ms gây tải DB | Partial index chỉ quét dòng chưa phát. Đo ở tuần 7, tăng interval khi rảnh (adaptive) nếu cần |
| Bảng outbox phình to | Job xóa sự kiện đã phát quá 7 ngày, ghi vào "Giới hạn" |

## Câu hỏi phỏng vấn tự luyện

1. Vì sao không publish thẳng lên Kafka sau khi commit?
2. Relay phát trùng thì xử lý thế nào? Vì sao Kafka exactly-once không giải quyết được?
3. Thứ tự sự kiện được đảm bảo tới đâu? Khi nào thứ tự bị đảo?
4. Debezium tốt hơn polling ở đâu? Vì sao bạn không dùng?
5. `SKIP LOCKED` hoạt động thế nào? Còn dùng được ở đâu khác (job queue)?
