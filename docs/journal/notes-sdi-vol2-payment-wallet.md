# Ghi chú: System Design Interview Vol. 2: Payment System và Digital Wallet

- **Nguồn:** sách của Alex Xu và Sahn Lam, hai chương *Payment System* và *Digital Wallet*
- **Đọc để hiểu:** Exactly-once, đối soát, saga
- **Liên quan trong Ledgerly:** [01-kien-truc §6.3–6.5](../01-kien-truc.md#63-nạp-tiền-qua-ngân-hàng-saga-có-trạng-thái-tuần-9), tuần 9 và 11
- **Ngày đọc:** 2026-10-01 (AI ghi chú, xem [ai-usage](../ai-usage.md))

> **Lưu ý:** không có bản sách trong phiên làm việc. Ghi chú này viết từ kiến thức chung về hai chương, **chưa đối chiếu từng trang**. Khi có sách thì đối chiếu lại và sửa chỗ nào sai.

## Ý chính

**Payment System**

- Hai luồng *pay-in* (thu tiền về) và *pay-out* (chi tiền ra). Các thành phần: payment service, payment executor gọi tới PSP (nhà cung cấp dịch vụ thanh toán), **ledger** ghi sổ kép, **wallet** giữ số dư.
- **Exactly-once** được ghép từ hai nửa: *at-least-once* nhờ retry (có backoff), và *at-most-once* nhờ **kiểm tra idempotency** (idempotency key, ràng buộc unique trong DB).
- **Đối soát (reconciliation)** so sổ nội bộ với file quyết toán mà PSP hoặc ngân hàng gửi định kỳ. Đây là lưới an toàn cuối cùng khi các hệ thống lệch nhau. Chênh lệch được phân loại: tự sửa được, sửa tay, hoặc không xác định được.
- Lỗi khi thanh toán: theo dõi trạng thái từng lệnh, retry có giới hạn, hàng đợi dead-letter cho lệnh thất bại liên tục, xử lý lệnh chậm hoặc chưa rõ kết quả.

**Digital Wallet**

- Bài toán chuyển tiền giữa các ví ở quy mô rất lớn. Khi số dư được chia ra nhiều node thì một lần chuyển tiền thành **giao dịch phân tán**.
- Các phương án được so sánh: 2PC, TC/C (Try-Confirm/Cancel), **Saga** (chuỗi bước cục bộ có bước bù trừ), và **event sourcing** (lưu chuỗi sự kiện bất biến, trạng thái là kết quả phát lại các sự kiện, nên tái tạo và kiểm chứng được).

## Áp dụng vào Ledgerly

- Đúng công thức exactly-once của §6.2: relay retry (at-least-once) cộng consumer khử trùng (at-most-once). Phía mock-bank: retry cộng khử trùng theo `merchantRef`.
- Đối soát tuần 11 chính là "lưới an toàn cuối": giải quyết trạng thái `UNKNOWN` (ngân hàng đã thu nhưng trả 500).
- Saga nạp/rút tiền tuần 9 là saga orchestration có bù trừ (`HELD → COMPENSATED`).
- Ledgerly **tránh** bài toán ví phân tán bằng cách giữ mọi ví trong **một** PostgreSQL (ADR-0001). Chuyển tiền là một transaction ACID cục bộ, không cần 2PC hay TC/C.

## Tự kiểm tra

- **Exactly-once ghép từ hai thứ gì?** At-least-once (retry) cộng at-most-once (khử trùng bằng idempotency key).
- **Đối soát bắt được lỗi gì mà retry không bắt được?** Lỗi "không biết": ngân hàng đã thu tiền nhưng phản hồi bị mất, hoặc trả lỗi. Lệch do bug, do thao tác tay, hay do bên ngoài tự đảo giao dịch. Retry chỉ chữa lỗi tạm thời. Đối soát so hai nguồn sự thật độc lập.
- **Ledgerly tránh bài toán ví phân tán thế nào?** Một database, khóa dòng có thứ tự, một transaction cho mỗi lần chuyển. Cái giá là không scale ngang được phần ghi. Đó là đánh đổi có chủ đích ở quy mô portfolio.

## Còn chưa rõ

- Chi tiết cách sách phân loại chênh lệch đối soát. Cần đối chiếu sách trước khi viết ADR-0010.
