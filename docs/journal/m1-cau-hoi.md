# Năm câu hỏi của mốc M1: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)) để luyện tập. Điều kiện qua mốc M1 là **tự trả lời thành tiếng mà không nhìn tài liệu**, việc này không ai làm thay được. Cách luyện: đọc câu hỏi, bấm giờ 2 phút, nói, rồi mới mở đáp án đối chiếu.

## 1. Sổ cái kép là gì? Vì sao tổng các entry của một giao dịch phải bằng 0?

Mỗi lần tiền dịch chuyển được ghi thành **ít nhất hai entry**: một bên mất, một bên nhận. Ledgerly ghi dấu nhìn từ phía account (ADR-0003): chuyển 150.000 đồng từ ví A sang ví B là hai entry `−150.000` cho A và `+150.000` cho B. Tổng bằng 0 nghĩa là **tiền không tự sinh ra hay mất đi**, chỉ di chuyển. Nạp tiền từ ngân hàng cũng vậy: account hệ thống `system:bank-settlement` nhận entry âm.

Hệ quả: (I1) mỗi giao dịch cân bằng, nên (I4) tổng số dư của mọi account luôn bằng 0. Một bug cộng tiền một phía sẽ phá I1 và bị chặn **lúc commit** bằng constraint trigger deferred.

## 2. Vì sao lưu cả `balance` lẫn `entries`? Nếu lệch thì tin cái nào?

- `entries` là **nguồn sự thật**: bất biến, chỉ thêm, có đủ lịch sử để kiểm toán.
- `balance` là **giá trị dẫn xuất được lưu sẵn** vì hai lý do: đọc số dư nhanh (không phải `SUM` hàng triệu dòng), và có **một dòng để khóa** (`FOR UPDATE`) khi kiểm tra đủ tiền. Ngoài ra `CHECK (allow_negative OR balance >= 0)` chặn ví người dùng bị âm ngay ở tầng DB.
- Lệch nhau thì **tin entries**. Lệch tức là có bug, nên I3 (`balance = SUM(entries.amount)`) được kiểm tra sau mọi load test. Không âm thầm sửa `balance`: dừng, điều tra, rồi sửa bằng bút toán điều chỉnh có ghi lý do.

## 3. Idempotency khác retry thế nào? Exactly-once được tạo ra từ hai thứ gì?

- **Retry** là phía gọi **gửi lại** khi không chắc request đã thành công. Nó cho *at-least-once*: thao tác chạy một hoặc nhiều lần.
- **Idempotency** là phía nhận bảo đảm **làm nhiều lần cũng chỉ có hiệu ứng một lần**: nhận diện request trùng qua `Idempotency-Key` (hoặc `eventId`, `merchantRef`) và trả lại kết quả cũ. Nó cho *at-most-once* về hiệu ứng.
- **Exactly-once về hiệu ứng = at-least-once (retry) + at-most-once (khử trùng).** Exactly-once về *giao nhận* qua mạng không tồn tại, vì không phân biệt được "server chưa làm" với "server làm rồi nhưng mất response".

## 4. Dual-write là gì? Cho một ví dụ mất dữ liệu cụ thể.

Dual-write là ghi vào **hai hệ thống** (DB và Kafka) bằng hai thao tác riêng, không có transaction chung. Ví dụ: service commit giao dịch chuyển tiền rồi gọi `kafka.send(TransferCompleted)`. Pod bị kill giữa hai bước, nên tiền đã chuyển mà người nhận không bao giờ được thông báo, và không còn dấu vết để gửi lại. Đảo thứ tự thì ngược lại: có thông báo "đã nhận tiền" cho một giao dịch bị rollback.

Cách giải là **transactional outbox**: ghi sự kiện vào bảng `outbox_events` **trong cùng transaction** với giao dịch, rồi relay đọc bảng và gửi lên Kafka. Sự kiện tồn tại khi và chỉ khi giao dịch commit. Relay có thể gửi trùng, nên consumer khử trùng theo `eventId`.

## 5. Vì sao chọn modular monolith trong khi đã từng làm microservice?

1. **Lõi bài toán cần ACID cục bộ.** Chuyển tiền phải nguyên tử. Tách ví và sổ cái thành hai service thì mỗi lần chuyển tiền thành saga phân tán, khó chứng minh đúng hơn nhiều.
2. **Đã chứng minh microservice ở dự án TypeScript/Python.** Dự án này cần chứng minh điều khác: tính đúng đắn dưới tải đồng thời, idempotency, outbox.
3. **Vẫn có ranh giới thật:** module trong `ledger-app` được ArchUnit kiểm tra, không truy cập `internal` của nhau. Có hai tiến trình riêng với ranh giới mạng thật (`mock-bank`, `notification-consumer`) để luyện timeout, retry, trùng lặp.
4. **Tách sau này dễ:** module giao tiếp qua `*Api` và sự kiện, nên tách thành service chỉ là đổi lời gọi trong process thành lời gọi mạng. Relay đã có thể chạy riêng bằng `ledgerly.outbox.relay.enabled`.

Chi tiết: [ADR-0001](../adr/0001-modular-monolith.md).
