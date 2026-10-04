# Tuần 3: Domain và chuyển tiền đơn luồng

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 19/10 – 25/10/2026 | 2: Lõi đúng đắn | | 14 giờ | ✅ Xong (merge PR #79–#83 ngày 03–04/10/2026) |

## Mục tiêu

1. Domain thuần Java, dùng đúng Java hiện đại: records, sealed interface, pattern matching.
2. API mở ví, xem số dư, xem lịch sử và chuyển tiền chạy đúng khi **chỉ có một luồng**.
3. Ranh giới module được ArchUnit kiểm tra từ ngày đầu.

## Công việc

| ID | Việc | Giờ | Đầu ra | Trạng thái |
|---|---|:-:|---|---|
| W03-01 | `shared`: record `Money` (long + `Currency`), `Math.addExact`, factory method kiểm tra hợp lệ | 1.5 | `MoneyTest` | ✅ #79 |
| W03-02 | `ledger.internal.domain`: `PostingRules`, nhận số dư hiện tại và các posting, trả về `PostingResult` (sealed) | 3 | `PostingRulesTest` | ✅ #79 |
| W03-03 | `ledger.internal.persistence`: repository bằng `JdbcClient` (insert transaction, entries, cập nhật balance) | 2 | | ✅ #80 |
| W03-04 | `LedgerApi` + `LedgerService` (`@Transactional`) | 1 | | ✅ #80 |
| W03-05 | `wallet`: `POST /v1/wallets`, `GET /v1/wallets/{id}`, `GET /v1/wallets/{id}/entries` (keyset) | 2 | | ✅ #83 |
| W03-06 | `wallet`: `POST /v1/transfers`, `GET /v1/transfers/{id}`, `POST /v1/admin/deposits` | 1.5 | | ✅ #83 |
| W03-07 | `shared.problem`: `@RestControllerAdvice` trả Problem Details (RFC 9457) | 1 | | ✅ #83 |
| W03-08 | `ArchitectureTest` (ArchUnit) với các quy tắc ở [01-kien-truc §4](../01-kien-truc.md#4-c4-mức-3-component-trong-ledger-app) | 1.5 | | ✅ #82 |
| W03-09 | `TransferApiIT` dùng `RestTestClient` (Spring Framework 7) + Testcontainers | 1.5 | | ✅ #83 |

## Ghi chú kỹ thuật

### Domain mẫu

```java
public record Money(long minorUnits, Currency currency) {
    public Money {
        Objects.requireNonNull(currency);
    }
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }
    // ...
}

public sealed interface TransferResult {
    record Completed(UUID transactionId, Instant at) implements TransferResult {}
    record InsufficientFunds(UUID walletId, Money available, Money requested) implements TransferResult {}
    record WalletNotFound(UUID walletId) implements TransferResult {}
    record SameWallet(UUID walletId) implements TransferResult {}
}

// Ở controller: switch dạng biểu thức, compiler kiểm tra đã xử lý đủ mọi nhánh
return switch (result) {
    case Completed c         -> ResponseEntity.created(uriOf(c)).body(TransferResponse.from(c));
    case InsufficientFunds f -> throw new ProblemException(INSUFFICIENT_FUNDS, f);
    case WalletNotFound n    -> throw new ProblemException(WALLET_NOT_FOUND, n);
    case SameWallet s        -> throw new ProblemException(SAME_ACCOUNT_TRANSFER, s);
};
```

Thêm một nhánh mới vào `TransferResult` thì mọi `switch` chưa xử lý nhánh đó sẽ **không compile được**. Đây là điểm đáng nói khi phỏng vấn về sealed types.

### Quy tắc ArchUnit tối thiểu

```java
@AnalyzeClasses(packages = "dev.ledgerly", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest static final ArchRule modulesFreeOfCycles =
        slices().matching("dev.ledgerly.(*)..").should().beFreeOfCycles();

    @ArchTest static final ArchRule internalsArePrivate =
        noClasses().that().resideOutsideOfPackage("dev.ledgerly.ledger..")
            .should().dependOnClassesThat().resideInAPackage("dev.ledgerly.ledger.internal..");
    // ... lặp lại cho mọi module, hoặc viết rule tổng quát bằng ArchCondition

    @ArchTest static final ArchRule domainIsFrameworkFree =
        noClasses().that().resideInAPackage("..internal.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..");
}
```

### Ở tuần này chưa làm

- Header `Idempotency-Key`: nhận và validate định dạng, nhưng **chưa** lưu (tuần 5).
- Khóa dòng: chưa có `FOR UPDATE` (tuần 4). Viết code theo cách dễ thêm khóa sau.
- Sự kiện: chưa có outbox (tuần 6).

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `MoneyTest` | Cộng tràn số ném `ArithmeticException`. Khác currency thì ném lỗi |
| `PostingRulesTest` | Đủ tiền thì `Posted`, thiếu tiền thì `InsufficientFunds`, tổng posting khác 0 thì bị từ chối |
| `TransferApiIT.happyPath` | 201, số dư hai ví thay đổi đúng, sinh 2 entry, `balance_after` đúng |
| `TransferApiIT.insufficientFunds` | 422 Problem Details, số dư không đổi, không sinh entry |
| `TransferApiIT.historyPagination` | Keyset trả đúng thứ tự, không trùng, không sót |
| `ArchitectureTest` | Toàn bộ rule xanh |

## Definition of Done

- [x] Gọi được toàn bộ endpoint tuần 3 bằng `curl`, có ví dụ trong `docs/journal` ([2026-W43](../journal/2026-W43.md#gọi-thử-bằng-curl))
- [x] `TransferResult` là sealed interface và controller dùng `switch` đầy đủ (`TransferController.create`)
- [x] Domain không import Spring (ArchUnit chứng minh: `ArchitectureTest.domainIsFrameworkFree`)
- [x] Coverage `internal.domain` ≥ 80% (100%, 38/38 dòng)

## Ghi chú khi thực hiện (03–04/10/2026)

- **Thứ tự làm khác bảng:** `ArchitectureTest` (W03-08) làm trước API, để các quy tắc kiểm tra code `wallet` ngay từ commit đầu.
- **`PostingRules` trả `PostingDecision`**, không trả thẳng `PostingResult`: quy tắc trả về các entry cần ghi, còn `PostingResult.Posted` cần id giao dịch mà chỉ tầng persistence mới có.
- **Ngoài kế hoạch:** kết quả sealed cho mở ví và nạp tiền (`OpenWalletResult`, `DepositResult`), bốn mã lỗi mới, chặn account `SYSTEM` làm nguồn chuyển tiền, `WalletApiIT` (16 test) và `ProblemDetailsAdviceTest` (3 test).
- **Chưa làm so với thiết kế:** trường `note` của chuyển tiền, API versioning của Spring Framework 7, `traceId` trong Problem Details. Xem [nhật ký](../journal/2026-W43.md#khác-với-tài-liệu-thiết-kế).

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Phân vân dùng JPA hay `JdbcClient` cho từng chỗ | Theo ADR-0002: đường nóng (posting, khóa) dùng `JdbcClient`. Đọc đơn giản thì dùng gì cũng được |
| Jackson 3 (`tools.jackson`) khác Jackson 2 | Chỉ dùng API Jackson qua Spring. Có vấn đề thì ghi vào nhật ký |

## Câu hỏi phỏng vấn tự luyện

1. Record khác class thường thế nào? Khi nào **không** nên dùng record?
2. Sealed interface giúp gì so với enum hoặc class hierarchy mở?
3. `@Transactional` hoạt động qua proxy ra sao? Vì sao gọi method trong cùng class (self-invocation) làm mất transaction?
4. Vì sao phân trang keyset tốt hơn offset cho lịch sử giao dịch?
