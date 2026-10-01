# ADR-0001: Modular monolith và hai tiến trình vệ tinh

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-01
- **Tuần:** 0
- **Liên quan:** [01-kien-truc §3–4](../01-kien-truc.md#3-c4-mức-2-container)

## Bối cảnh

Ledgerly cần chứng minh tính đúng đắn của giao dịch tiền: không chi tiêu trùng, idempotency, nhất quán giữa DB và sự kiện. Tác giả **đã có** dự án microservice bằng TypeScript và Python, nên chia nhỏ thêm một lần nữa không chứng minh được điều gì mới.

Các lực tác động:

- Ngân sách khoảng 160 giờ, một người làm.
- Ghi tiền cần **transaction ACID cục bộ**. Tách ví và sổ cái thành hai service sẽ biến mọi lần chuyển tiền thành giao dịch phân tán.
- Vẫn cần **ranh giới mạng thật** để luyện xử lý lỗi: timeout, retry, phát trùng.
- Cần một câu trả lời mạch lạc cho câu hỏi phỏng vấn "sao không làm microservice?".

## Các phương án đã cân nhắc

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| **A. Microservice** (Order, Payment, Wallet, Notification...) | Đúng từ khóa thị trường | Lặp lại kinh nghiệm cũ. Chuyển tiền thành saga ngay từ đầu. Chi phí vận hành lớn, dễ bỏ dở |
| **B. Monolith thường** (một package lớn) | Nhanh nhất | Không có ranh giới, dễ thành "big ball of mud", không có gì để nói về kiến trúc |
| **C. Modular monolith + ArchUnit, cộng 2 tiến trình vệ tinh** | Transaction cục bộ cho lõi tiền. Ranh giới module được máy kiểm tra. Vẫn có ranh giới mạng thật (mock-bank, consumer) | Phải tự kỷ luật đặt ranh giới. Không "scale độc lập" từng module |

## Quyết định

Chúng tôi sẽ xây **`ledger-app` là một modular monolith**: mỗi module là một package cấp cao (`ledger`, `wallet`, `idempotency`, `outbox`, `bankgateway`, `topup`, `reconciliation`, `shared`), phần riêng tư nằm trong `internal`, và ranh giới được **ArchUnit** kiểm tra trong CI.

Ngoài lõi chỉ có hai tiến trình riêng, mỗi cái có lý do tồn tại:

- **`mock-bank`**: giả lập hệ thống ngoài không tin cậy, để có ranh giới HTTP thật.
- **`notification-consumer`**: consumer Kafka độc lập, để chứng minh khử trùng ở phía nhận.

## Hệ quả

- **Tích cực:** chuyển tiền là một transaction PostgreSQL duy nhất, dễ chứng minh đúng. Tách module thành service sau này dễ hơn, vì ranh giới đã sạch và giao tiếp qua `*Api` cùng sự kiện. Có câu chuyện kiến trúc rõ ràng để kể khi phỏng vấn.
- **Tiêu cực:** không minh họa service discovery, API gateway hay distributed tracing nhiều service (tracing vẫn có giữa 3 tiến trình).
- **Cần theo dõi:** nếu một module cần scale khác hẳn phần còn lại (ví dụ relay), có thể chạy cùng artifact với vai trò khác (`ledgerly.outbox.relay.enabled`) trước khi nghĩ đến tách service.

## Bằng chứng

- `ArchitectureTest` (tuần 3).
- Báo cáo nghiên cứu: [research/](../research/).
