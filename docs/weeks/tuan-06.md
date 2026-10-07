# Tuần 6: Transactional outbox, Kafka và consumer idempotent

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 09/11 – 15/11/2026 | 2: Lõi đúng đắn | **M3**: Nhất quán | 13.5 giờ | ✅ Xong (merge PR #98–#102 ngày 08/10/2026) |

## Mục tiêu

Mỗi giao dịch đã commit **chắc chắn** sinh ra đúng một sự kiện trên Kafka, kể cả khi Kafka sập hoặc relay bị kill. Bản trùng (nếu có) bị consumer loại bỏ và **đếm được**.

## Công việc

| ID | Việc | Giờ | Đầu ra | Trạng thái |
|---|---|:-:|---|---|
| W06-01 | `ledger-contracts`: `EventEnvelope<T>`, record `TransferCompleted`, hằng số tên topic | 1.5 | `EventEnvelope`, `TransferCompleted`, `Topics`, file sự kiện mẫu | ✅ #98 |
| W06-02 | Flyway `V4__outbox_events.sql`, partial index `WHERE published_at IS NULL` | 0.5 | `OutboxEventRepository` | ✅ #99 |
| W06-03 | `OutboxWriter.append(...)` với `Propagation.MANDATORY`, gọi trong transaction chuyển tiền | 1 | `OutboxWriter`, `OutboxService` | ✅ #99 |
| W06-04 | `OutboxRelay`: `@Scheduled(fixedDelay)`, lấy theo lô bằng `FOR UPDATE SKIP LOCKED`, gửi, chờ ack, đánh dấu đã phát | 3 | `OutboxRelay`, `KafkaEventPublisher`, `OutboxRelayScheduler` | ✅ #100 |
| W06-05 | Relay chịu lỗi: Kafka lỗi thì rollback lô, backoff tăng dần, metric `outbox.pending` và `outbox.oldest.age` | 1 | `Backoff`, `OutboxMetrics` | ✅ #100 |
| W06-06 | Cấu hình producer `acks=all`, `enable.idempotence=true`. Bean `NewTopic` (3 partition) | 0.5 | `OutboxTopics`, `application.yaml` | ✅ #100 |
| W06-07 | `notification-consumer`: Flyway `V1` (`processed_events`, `notifications`), `@KafkaListener` khử trùng trong một transaction | 2.5 | `TransferEventListener`, `NotificationService` | ✅ #101 |
| W06-08 | Test: `OutboxAtomicityIT`, `OutboxRelayIT`, `RelayCrashDuplicateIT`, `ConsumerDedupIT` | 2.5 | | ✅ #99, #100, #101 |
| W06-09 | **ADR-0006**: outbox + polling relay, so với dual-write và Debezium CDC | 1 | [ADR-0006](../adr/0006-transactional-outbox-voi-polling-relay.md) | ✅ #102 |

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

- [x] Toàn bộ test xanh (xem [kiểm chứng cuối](../journal/2026-W46.md#kiểm-chứng-cuối), và CI của từng PR)
- [x] Dừng Kafka thủ công (`docker compose stop kafka`): API vẫn trả 201, outbox tồn đọng. Bật lại thì outbox xả hết (30 × 201, tồn đọng 30, xả hết sau 3 giây: [nhật ký](../journal/2026-W46.md#chạy-tay-với-hai-ứng-dụng-thật))
- [x] ADR-0006 Accepted (do AI soạn và đặt trạng thái, merge theo yêu cầu của tác giả ngày 08/10/2026)
- [x] **Mốc M3 đạt** (đủ ba điều kiện, PR #98–#102 đã merge ngày 08/10/2026)

## Ghi chú khi thực hiện (07/10/2026)

- **Relay dùng `TransactionTemplate`** như đoạn mã mẫu ở dưới, và tách thành `OutboxRelay` (một vòng) với `OutboxRelayScheduler` (lịch và backoff). Property `ledgerly.outbox.relay.enabled` tắt scheduler, không tắt relay.
- **`RelayCrashDuplicateIT` chỉ khẳng định điều 1** trong ba điều của mục "Giả lập crash relay". Điều 2 và 3 (bảng `notifications`, metric `notification.duplicates`) cần consumer, là một ứng dụng Spring Boot khác không chạy chung JVM test được. Chúng được khẳng định ở `ConsumerDedupIT` với bản trùng tự gửi, và trong lần chạy tay với `kill -9` thật.
- **`EventContractTest` có hai nửa:** nửa consumer đọc file mẫu, nửa producer nằm trong `OutboxRelayIT` (message trên Kafka có đúng tập trường của file mẫu).
- **Khác bản thiết kế:** thêm cột `topic`, `created_at` dùng `clock_timestamp()`, nạp tiền nội bộ không phát sự kiện, thông báo chỉ cho ví nhận.
- **Ngoài kế hoạch:** `OutboxEventRepositoryIT`, `OutboxRelaySchedulerIT`, `OutboxRelaySchedulerTest`, `BackoffTest`, cột `attempts` được ghi thật, `linger.ms=0`, `scripts/invariants-events.sql`, và sửa chú thích sai trong `TestcontainersConfiguration`.
- **Bài `kill -9` của tuần 10 đã chạy thử bằng tay:** 3.050 giao dịch, 3.050 thông báo, 22 bản trùng được đếm.
- Giới hạn đã biết: xem [nhật ký](../journal/2026-W46.md#giới-hạn-đã-biết).

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
