# Tuần 4: Concurrency và bất biến

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 26/10 – 01/11/2026 | 2: Lõi đúng đắn | **M2**: Lõi sổ cái đúng | 14 giờ | ✅ Xong (merge PR #85–#89 ngày 04/10/2026) |

## Mục tiêu

**Chứng minh bằng máy** rằng chuyển tiền đồng thời không gây chi tiêu trùng, không deadlock và bảo toàn tổng tiền. Đây là tuần quan trọng nhất của dự án.

## Công việc

| ID | Việc | Giờ | Đầu ra | Trạng thái |
|---|---|:-:|---|---|
| W04-01 | Khóa account theo thứ tự: `SELECT ... WHERE id = ANY(?) ORDER BY id FOR UPDATE` | 2 | `AccountRepository.lockAll()` | ✅ #86 |
| W04-02 | `SET LOCAL lock_timeout = '2s'`, map lỗi `55P03` sang 503, metric `ledgerly.posting.lock.wait` | 1 | | ✅ #86 |
| W04-03 | `ConcurrentTransferIT`: 200 virtual threads, 10 ví, 10.000 lần chuyển ngẫu nhiên | 3 | | ✅ #86 |
| W04-04 | `DeadlockFreedomIT`: 1.000 cặp A→B và B→A chạy đồng thời, không có lỗi `40P01` | 1.5 | | ✅ #86 |
| W04-05 | **Thí nghiệm**: bỏ `ORDER BY`, chạy lại W04-04 để thấy deadlock. Ghi số liệu vào ADR rồi revert | 1 | | ✅ #89 |
| W04-06 | Property-based test bằng jqwik: model-based testing, so sánh DB với model trong bộ nhớ | 3 | `LedgerModelProperties` | ✅ #87 |
| W04-07 | `scripts/invariants.sql` và helper `InvariantChecker` dùng trong test | 1 | | ✅ #85 |
| W04-08 | **ADR-0004**: khóa bi quan có thứ tự, READ COMMITTED (so sánh với SERIALIZABLE và khóa lạc quan) | 1.5 | | ✅ #89 |

## Ghi chú kỹ thuật

### Khung test concurrency

```java
@Test
void concurrentTransfersPreserveInvariants() throws Exception {
    var wallets = createWallets(10, Money.vnd(1_000_000));
    long totalBefore = sumBalances(wallets);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new CountDownLatch(1);
        var futures = IntStream.range(0, 10_000).mapToObj(i -> executor.submit(() -> {
            start.await();                               // mọi luồng xuất phát cùng lúc
            var pair = randomDistinctPair(wallets);
            return transfer(pair.from(), pair.to(), randomAmount());
        })).toList();
        start.countDown();
        for (var f : futures) f.get(60, SECONDS);        // không có exception lạ
    }

    assertThat(sumBalances(wallets)).isEqualTo(totalBefore);         // I4: bảo toàn
    assertThat(minBalance(wallets)).isGreaterThanOrEqualTo(0);       // I2: không âm
    invariantChecker.assertAllTransactionsBalanced();                // I1
    invariantChecker.assertBalancesMatchEntries(wallets);            // I3
}
```

Lưu ý **giới hạn số request thật sự chạm DB**: 200 virtual threads nhưng pool HikariCP chỉ có khoảng 10–20 connection. Đây là chủ đề sẽ quay lại ở tuần 10.

### Model-based property test (jqwik)

- Sinh ngẫu nhiên một chuỗi lệnh: `Deposit(w, x)`, `Transfer(a, b, x)`, `Transfer(a, a, x)`, `Transfer(a, b, quá số dư)`...
- Áp từng lệnh lên **model** (một `Map<UUID, Long>` trong bộ nhớ) và lên **hệ thống thật**.
- Sau mỗi lệnh: kết quả (thành công hoặc lỗi gì) và số dư phải khớp nhau.
- jqwik tự **thu nhỏ (shrink)** chuỗi lệnh khi tìm ra lỗi, để có ví dụ ngắn nhất tái hiện bug.

### Truy vấn bất biến (`scripts/invariants.sql`)

```sql
-- I1: mọi transaction cân bằng
SELECT transaction_id, sum(amount) FROM entries GROUP BY transaction_id HAVING sum(amount) <> 0;
-- I3: balance khớp tổng entry
SELECT a.id, a.balance, coalesce(sum(e.amount), 0) AS entries_sum
FROM accounts a LEFT JOIN entries e ON e.account_id = a.id
GROUP BY a.id HAVING a.balance <> coalesce(sum(e.amount), 0);
-- I4: tổng toàn hệ thống (kể cả account SYSTEM) bằng 0
SELECT sum(balance) FROM accounts;
```

Kỳ vọng: hai truy vấn đầu trả **0 dòng**, truy vấn thứ ba trả **0**.

## Kiểm thử bắt buộc

| Test | Khẳng định |
|---|---|
| `ConcurrentTransferIT` | I1–I4 đúng, xanh **10 lần liên tiếp** (`@RepeatedTest(10)` khi chạy kiểm tra) |
| `DeadlockFreedomIT` | 0 lỗi `40P01` |
| `HotWalletDrainIT` | 500 luồng cùng rút từ 1 ví có 100 đơn vị, mỗi lần 1 đơn vị: đúng 100 lần thành công, 400 lần `InsufficientFunds` |
| `LedgerModelProperties` | 1.000 lượt thử, không vi phạm |

## Definition of Done

- [x] Tất cả test trên xanh trên CI (CI của PR #86, #87 chạy đủ 10.000 lần chuyển)
- [x] ADR-0004 có số liệu thí nghiệm W04-05 (có và không có `ORDER BY`): [bốn cách khóa, mỗi cách 3 lượt](../adr/0004-khoa-bi-quan-co-thu-tu.md#câu-hỏi-2-lấy-khóa-theo-cách-nào)
- [x] `scripts/invariants.sql` chạy được trên DB compose (bốn câu đều trả 0 dòng)
- [x] Đặt ngưỡng JaCoCo ≥ 80% cho `internal.domain`, build fail nếu thấp hơn (PR #88, đã thử với tỉ lệ 0,74)
- [x] **Mốc M2 đạt** (đủ bốn điều kiện, PR #85–#89 đã merge ngày 04/10/2026)

## Ghi chú khi thực hiện (04/10/2026)

- **Thứ tự làm khác bảng:** `InvariantChecker` (W04-07) làm trước, vì mọi test đồng thời đều dùng nó.
- **`FOR NO KEY UPDATE`** thay cho `FOR UPDATE` trong W04-01, theo ghi chú ở #28. Lý do nằm trong ADR-0004.
- **Thí nghiệm W04-05 mở rộng thành bốn cách khóa.** Chỉ bỏ `ORDER BY` vẫn deadlock 72–74 lần trên 2.000 lần chuyển.
- **CI chạy đủ 10.000 lần chuyển.** Phương án giảm xuống 2.000 không cần dùng. Vẫn đổi được bằng `-Pledgerly.test.concurrentTransfers=<n>`.
- **Chạy 10 lần liên tiếp** bằng vòng lặp `./gradlew :ledger-app:integrationTest --tests '*ConcurrentTransferIT' --rerun`, không dùng `@RepeatedTest`.
- **Ngoài kế hoạch:** `LockTimeoutIT`, `InvariantCheckerIT`, I2 trong `invariants.sql`, sửa fixture của `SchemaConstraintsIT`, bước dịch `55P03` (Spring không tự dịch).
- Giới hạn đã biết: xem [nhật ký](../journal/2026-W44.md#giới-hạn-đã-biết).

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Test concurrency flaky | Không dùng `sleep`. Dùng `CountDownLatch`, đặt timeout rõ ràng, ghi seed ngẫu nhiên vào log để tái hiện |
| Test chạy quá lâu trên CI | Giảm 10.000 xuống 2.000 trên CI qua system property. Bản đầy đủ chạy local |

## Câu hỏi phỏng vấn tự luyện

1. Lost update là gì? READ COMMITTED có ngăn được không? `FOR UPDATE` giúp thế nào?
2. Vì sao khóa theo thứ tự id thì không deadlock? Chứng minh ngắn gọn.
3. Khi nào khóa lạc quan tốt hơn khóa bi quan? Vì sao hot account là trường hợp xấu cho khóa lạc quan?
4. Ở SERIALIZABLE, PostgreSQL làm gì khi phát hiện xung đột? Ứng dụng phải xử lý ra sao?
5. Virtual thread có giúp transaction nhanh hơn không? Vì sao?
