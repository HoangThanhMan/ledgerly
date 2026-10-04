# Architecture Decision Records (ADR)

ADR ghi lại **một quyết định kiến trúc quan trọng**: bối cảnh, lựa chọn và hệ quả. Định dạng theo Michael Nygard, xem [mẫu](0000-template.md).

## Quy tắc

- Mỗi ADR dài 1–2 trang, đánh số tăng dần, **không xóa**.
- Quyết định bị thay thế thì đổi trạng thái thành `Superseded by ADR-XXXX`, ADR mới ghi `Supersedes ADR-YYYY`.
- Viết ADR **trong tuần đưa ra quyết định**, không viết bù ở cuối dự án.
- Trạng thái: `Proposed` → `Accepted` → (`Deprecated` | `Superseded`).

## Danh mục

| # | Tiêu đề | Trạng thái | Tuần |
|---|---|---|:-:|
| [0001](0001-modular-monolith.md) | Modular monolith và hai tiến trình vệ tinh | Accepted | 0 |
| [0002](0002-postgresql-va-sql-tuong-minh.md) | PostgreSQL, SQL tường minh cho đường nóng | Accepted | 0 |
| [0003](0003-bieu-dien-tien-te.md) | Biểu diễn tiền tệ và quy ước dấu của bút toán | Accepted | 1 |
| [0004](0004-khoa-bi-quan-co-thu-tu.md) | Khóa bi quan có thứ tự ở READ COMMITTED | Accepted | 4 |
| 0005 | Idempotency key trong PostgreSQL, thiết kế hai pha | *Dự kiến* | 5 |
| 0006 | Transactional outbox với polling relay (không dùng dual-write hay Debezium) | *Dự kiến* | 6 |
| 0007 | Observability: OpenTelemetry qua Boot starter + Grafana LGTM | *Dự kiến* | 7 |
| 0008 | Saga orchestration cho nạp/rút tiền | *Dự kiến* | 9 |
| 0009 | Chống quá tải: `@ConcurrencyLimit` và kích thước connection pool | *Dự kiến* | 10 |
| 0010 | Chiến lược cho hot account (theo kết quả benchmark) | *Dự kiến* | 11 |
