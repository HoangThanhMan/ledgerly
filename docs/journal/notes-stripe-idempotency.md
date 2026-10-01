# Ghi chú: Stripe: Designing robust and predictable APIs with idempotency

- **Nguồn:** https://stripe.com/blog/idempotency, đối chiếu thêm với tài liệu API https://docs.stripe.com/api/idempotent_requests
- **Đọc để hiểu:** Mô hình `Idempotency-Key`
- **Liên quan trong Ledgerly:** [01-kien-truc §6.1](../01-kien-truc.md#61-chuyển-tiền-idempotent-tuần-35), [tuần 5](../weeks/tuan-05.md), ADR-0005
- **Ngày đọc:** 2026-10-01 (AI đọc và ghi chú, xem [ai-usage](../ai-usage.md))

## Ý chính

- Trong hệ phân tán, client gửi request mà mất kết nối thì **không biết** server đã làm hay chưa. Bài chia lỗi ra ba thời điểm: (1) request chưa tới server, (2) server đang làm dở thì lỗi, (3) server làm xong nhưng response bị mất.
- Cách giải: client sinh một **khóa duy nhất** cho mỗi thao tác và gửi kèm header `Idempotency-Key`. Server gắn trạng thái xử lý với khóa đó. Ở lỗi (3), server trả lại kết quả đã lưu. Ở lỗi (2), nếu DB ACID đã rollback thì làm lại an toàn.
- Retry phải **có trách nhiệm**: exponential backoff (chờ khoảng 2^n) cộng **jitter** ngẫu nhiên, để hàng nghìn client không retry cùng một nhịp (thundering herd).
- Tài liệu API của Stripe nói rõ hơn bài blog: lưu status và body của request **đầu tiên**, kể cả lỗi 500. So tham số với request gốc, khác thì báo lỗi. Request lỗi validate hoặc **đụng một request cùng key đang chạy** thì không lưu, client retry được. Key được dọn sau ít nhất 24 giờ. Key dài tối đa 255 ký tự, nên dùng UUID v4.

## Áp dụng vào Ledgerly

| Stripe | Ledgerly |
|---|---|
| So tham số với request gốc | `request_hash` trên DTO đã chuẩn hóa, khác thì 422 |
| Đụng request đang chạy thì không lưu | `IN_PROGRESS` chưa hết hạn thì 409 + `Retry-After` |
| Lưu cả lỗi 500 | **Khác:** Tx2 lỗi kỹ thuật thì rollback, chưa có hiệu ứng nào, nên thả key cho retry thay vì lưu 500 |
| Dọn key sau 24 giờ | `expires_at` TTL 24 giờ, job dọn theo lô (W05-06) |
| Key tối đa 255 ký tự | Tối đa 64 ký tự, khuyến nghị UUID |

## Tự kiểm tra

- **Client nên sinh key thế nào, retry thế nào?** UUID v4 (hoặc chuỗi ngẫu nhiên đủ entropy), sinh **một lần cho một thao tác nghiệp vụ** và giữ nguyên qua mọi lần retry. Retry theo exponential backoff có jitter, và có giới hạn số lần.
- **Khi nào server lưu kết quả?** Khi endpoint đã bắt đầu thực thi nghiệp vụ. Lỗi validate (400) và xung đột đồng thời (409) thì không lưu.
- **Giống và khác thiết kế hai pha của Ledgerly?** Giống ở ba nhánh replay / so tham số / xung đột. Khác ở chỗ Ledgerly thả key khi lỗi kỹ thuật, và dùng `lease_token` để một request chậm không ghi đè kết quả của request đã giành lại key.

## Còn chưa rõ

- Stripe không nói key được **scope** theo gì. Thực tế họ scope theo tài khoản API. Ledgerly chưa có xác thực (non-goal) nên key là toàn cục. Cần ghi điều này vào ADR-0005.
