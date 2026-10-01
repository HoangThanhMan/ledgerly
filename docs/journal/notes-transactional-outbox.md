# Ghi chú: microservices.io: Transactional outbox

- **Nguồn:** https://microservices.io/patterns/data/transactional-outbox.html
- **Đọc để hiểu:** Vì sao không dual-write
- **Liên quan trong Ledgerly:** [01-kien-truc §6.2](../01-kien-truc.md#62-outbox-relay-và-consumer-idempotent-tuần-6), [tuần 6](../weeks/tuan-06.md), ADR-0006
- **Ngày đọc:** 2026-10-01 (AI đọc và ghi chú, xem [ai-usage](../ai-usage.md))

## Ý chính

- **Vấn đề:** service phải vừa cập nhật DB vừa gửi message lên broker, một cách nguyên tử. 2PC thường không có hoặc không nên dùng. Gửi message sau khi commit thì service có thể crash trước khi gửi. Ngoài ra thứ tự message của cùng một aggregate phải được giữ.
- **Giải pháp:** ghi message vào bảng **outbox** trong **cùng transaction** cập nhật dữ liệu nghiệp vụ. Một tiến trình *message relay* đọc outbox và gửi lên broker một cách bất đồng bộ.
- **Hai cách relay:** *polling publisher* (định kỳ truy vấn bảng outbox) và *transaction log tailing* (đọc WAL/binlog, như Debezium).
- **Được:** không cần 2PC. Message được gửi **khi và chỉ khi** transaction commit. Giữ được thứ tự.
- **Mất:** dễ quên ghi outbox ở một chỗ nào đó. Relay có thể gửi **trùng** (crash sau khi gửi, trước khi đánh dấu đã gửi), nên consumer phải idempotent.

## Áp dụng vào Ledgerly

- `OutboxWriter.append` với `Propagation.MANDATORY` (W06-03) chống "quên": gọi ngoài transaction thì ném lỗi ngay.
- Relay là polling publisher với `FOR UPDATE SKIP LOCKED` (W06-04). Consumer khử trùng bằng `processed_events` (W06-07). Đây đúng là phần "mất" mà bài cảnh báo, được giải bằng thiết kế.
- `UNIQUE (aggregate_id, event_type)` bảo đảm mỗi giao dịch đúng một sự kiện (I6).

## Tự kiểm tra

- **Ví dụ dual-write mất dữ liệu:** `transferService` commit giao dịch chuyển tiền rồi gọi `kafka.send(TransferCompleted)`. Pod bị kill giữa hai bước: tiền đã chuyển nhưng không có thông báo, và không ai biết để gửi lại. Đảo thứ tự (gửi trước, commit sau) thì ngược lại: có thông báo cho một giao dịch bị rollback.
- **Polling và log tailing khác nhau thế nào?** Polling đơn giản, chỉ cần SQL, dễ test, nhưng tốn truy vấn định kỳ và có độ trễ bằng chu kỳ poll. Log tailing có độ trễ thấp và không tải bảng, nhưng cần hạ tầng CDC (Debezium, quyền đọc replication slot) và vận hành phức tạp hơn.
- **Vì sao chỉ at-least-once?** Gửi lên broker và đánh dấu đã gửi trong DB là hai hệ thống, không nguyên tử. Crash giữa hai bước thì lần sau gửi lại. Consumer phải khử trùng theo `eventId`.

## Còn chưa rõ

- Thứ tự toàn cục giữa nhiều aggregate có cần không? Ledgerly chỉ cần thứ tự **theo aggregate** (cùng key thì cùng partition), nên ADR-0006 nên ghi rõ giới hạn này.
