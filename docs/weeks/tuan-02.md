# Tuần 2: Chất lượng build và schema lõi

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 12/10 – 18/10/2026 | 1: Nền móng | | 13 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

1. Mọi lỗi format, lỗi null và lỗi phổ biến **bị chặn ngay lúc build**, trước khi viết dòng nghiệp vụ đầu tiên.
2. Tách test nhanh (unit) và test chậm (integration) thành hai suite riêng.
3. Có schema sổ cái lõi với các ràng buộc phòng thủ ở tầng database.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W02-01 | Convention plugin `ledgerly.quality`: Spotless (Palantir Java Format), Error Prone + NullAway, JaCoCo | 3 | `build-logic/.../ledgerly.quality.gradle.kts` |
| W02-02 | Đánh dấu `@NullMarked` (JSpecify) trong `package-info.java` của mọi module | 0.5 | |
| W02-03 | JVM Test Suite `integrationTest`. Chuyển test Testcontainers sang đó. `check` phụ thuộc suite này | 2 | `src/integrationTest/java` |
| W02-04 | Testcontainers dùng chung container cho cả suite (singleton) để test nhanh hơn | 1 | `AbstractIntegrationTest` |
| W02-05 | Flyway `V1__ledger_core.sql`: `accounts`, `ledger_transactions`, `entries`, constraint, trigger | 3 | |
| W02-06 | Flyway `V2__system_accounts.sql`: seed `system:funding`, `system:bank-settlement`, `system:withdrawal-suspense` | 0.5 | |
| W02-07 | `SchemaConstraintsIT`: kiểm tra trigger và constraint hoạt động | 1.5 | |
| W02-08 | CI: cache Gradle, upload báo cáo test khi lỗi, tóm tắt coverage, badge trong README | 1 | |
| W02-09 | `dependabot.yml` (Gradle + GitHub Actions, hằng tuần) | 0.5 | |

## Ghi chú kỹ thuật

### `V1__ledger_core.sql` (phác thảo)

```sql
CREATE TABLE accounts (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    type           TEXT        NOT NULL CHECK (type IN ('USER_WALLET', 'SYSTEM')),
    code           TEXT        UNIQUE,
    currency       CHAR(3)     NOT NULL DEFAULT 'VND',
    balance        BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE ledger_transactions (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    type       TEXT        NOT NULL,
    reference  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    amount         BIGINT      NOT NULL CHECK (amount <> 0),
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX entries_account_id_id ON entries (account_id, id);   -- keyset pagination
CREATE INDEX entries_transaction_id ON entries (transaction_id);

-- P2: append-only
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER entries_append_only BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- I1: tổng entry của một transaction = 0, kiểm tra lúc COMMIT
CREATE FUNCTION assert_transaction_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT sum(amount) FROM entries WHERE transaction_id = NEW.transaction_id) <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced', NEW.transaction_id;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER entries_balanced AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
```

> **Hệ quả cho test:** vì `entries` không cho `DELETE`, test **không dọn dữ liệu bằng TRUNCATE**. Mỗi test tự tạo account mới (UUID), và bất biến được kiểm tra trên chính các account đó. Bất biến toàn cục (tổng mọi số dư bằng 0) vẫn đúng bất kể dữ liệu cũ.

### Cấu hình chất lượng

- Spotless chạy `spotlessCheck` trong `check`. Trên máy local dùng `./gradlew spotlessApply`.
- Error Prone ở mức *error* cho nhóm lỗi chắc chắn là bug, NullAway ở chế độ JSpecify.
- JaCoCo chỉ báo cáo ở tuần này. Từ tuần 4 mới đặt ngưỡng (mục tiêu ≥ 80% line coverage cho `internal.domain`).
- **Kiểm tra tương thích** với Java 25 và Boot 4 trước khi chốt phiên bản plugin. Ghi lại phiên bản vào `libs.versions.toml`.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `SchemaConstraintsIT.updatingEntryIsRejected` | `UPDATE entries ...` ném `PSQLException` có thông báo `append-only` |
| `SchemaConstraintsIT.unbalancedTransactionIsRejectedAtCommit` | Insert 1 entry `+100` rồi commit thì lỗi |
| `SchemaConstraintsIT.negativeBalanceIsRejected` | Ví `USER_WALLET` có `balance = -1` thì vi phạm `balance_non_negative` |
| `SchemaConstraintsIT.systemAccountMayGoNegative` | Account `SYSTEM` có `allow_negative` được phép âm |

## Definition of Done

- [ ] `./gradlew check` chạy Spotless, Error Prone, unit test, integration test và JaCoCo
- [ ] Cố ý thêm một lỗi null hoặc format thì build đỏ (thử rồi revert)
- [ ] `./gradlew test` (chỉ unit) chạy dưới 10 giây
- [ ] Bốn test schema xanh
- [ ] CI xanh, README có badge

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Error Prone hoặc NullAway chưa hỗ trợ JDK 25 | Ghim phiên bản mới nhất có hỗ trợ. Nếu chưa có thì tạm dùng SpotBugs và ghi lý do |
| Trigger deferred làm chậm insert | Chấp nhận. Đo lại ở tuần 7 và ghi kết quả vào ADR nếu đáng kể |

## Câu hỏi phỏng vấn tự luyện

1. Vì sao không dùng H2 cho test?
2. Constraint trigger `DEFERRABLE INITIALLY DEFERRED` hoạt động thế nào? Vì sao phải deferred?
3. Vì sao chặn bất biến ở **cả** code Java **và** database?
4. Unit test khác integration test ở đâu trong dự án này?
