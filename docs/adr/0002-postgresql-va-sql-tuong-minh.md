# ADR-0002: PostgreSQL, SQL tường minh cho đường nóng

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-01
- **Tuần:** 0
- **Liên quan:** ADR-0001, ADR-0004 (dự kiến)

## Bối cảnh

Tính đúng đắn của sổ cái phụ thuộc vào việc kiểm soát **chính xác** khóa dòng (`FOR UPDATE`, `SKIP LOCKED`), isolation level, constraint và trigger. Cần một database:

- hỗ trợ đầy đủ các cơ chế trên,
- chạy được trong Testcontainers và Docker Compose,
- phổ biến trên thị trường (Stack Overflow 2025: PostgreSQL được 55,6% người dùng).

Cũng cần quyết định cách truy cập dữ liệu từ Java: ORM (JPA/Hibernate) hay SQL tường minh.

## Các phương án đã cân nhắc

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| **MySQL 8** | Phổ biến ở Việt Nam | `SKIP LOCKED` có nhưng hành vi khóa (gap lock ở REPEATABLE READ) khó giải thích hơn. Constraint trigger deferred kém hơn |
| **PostgreSQL 18** | `FOR UPDATE SKIP LOCKED`, constraint trigger `DEFERRABLE`, `uuidv7()` có sẵn, `jsonb` | Cần cấu hình nhiều database cho nhiều service (giải quyết bằng init script) |
| **Chỉ dùng JPA/Hibernate** | Ít code | SQL bị ẩn: khó thấy khóa nào được lấy, khi nào flush. Dễ dính N+1 |
| **Chỉ dùng JdbcClient** | Kiểm soát hoàn toàn | Nhiều code lặp cho phần CRUD đơn giản |
| **Kết hợp** | Đúng công cụ cho đúng việc | Hai cách truy cập trong một codebase |

## Quyết định

Chúng tôi sẽ dùng **PostgreSQL 18**, mỗi service một database riêng trong cùng một instance khi chạy local. Schema do **Flyway** quản lý (`ddl-auto=validate`).

Cách truy cập dữ liệu:

- **Đường nóng** (posting, khóa account, idempotency, outbox relay, saga worker) dùng **`JdbcClient` với SQL tường minh**.
- Phần **đọc hoặc CRUD đơn giản** (danh sách ví, báo cáo đối soát) được phép dùng Spring Data JPA nếu nó làm code gọn hơn.
- Ràng buộc nghiệp vụ quan trọng được **khóa ở database** (CHECK, trigger), không chỉ ở Java.

## Hệ quả

- **Tích cực:** mọi câu SQL quan trọng nằm ngay trong code, dễ review và dễ trích ra khi phỏng vấn. Kỹ năng SQL, khóa và isolation chuyển được sang Oracle hay MySQL.
- **Tiêu cực:** viết nhiều SQL hơn. Phải tự map `ResultSet` sang record.
- **Cần theo dõi:** nếu JPA gây hành vi khó đoán (flush ngầm trong transaction posting) thì loại JPA khỏi module đó.

## Bằng chứng

- Spike khóa ở [tuần 1](../weeks/tuan-01.md) (W01-08).
- `SchemaConstraintsIT` (tuần 2).
