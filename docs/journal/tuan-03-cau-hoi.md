# Câu hỏi phỏng vấn tuần 3: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu.

## 1. Record khác class thường thế nào? Khi nào **không** nên dùng record?

Record là class **chỉ để mang dữ liệu bất biến**. Khai báo `record Money(long amount, Currency currency)` thì compiler sinh field `private final`, constructor, accessor (`amount()`, không phải `getAmount()`), `equals`, `hashCode` và `toString` dựa trên **toàn bộ** component. Record ngầm định `final` và không kế thừa class khác, nhưng implement interface được. Compact constructor là chỗ đặt kiểm tra và sao chép phòng thủ: `PostingRequest` gọi `List.copyOf(postings)` ở đó, nên không ai sửa được danh sách sau khi tạo.

Trong Ledgerly: `Money`, `Posting`, `EntryDraft`, các nhánh của `PostingResult` và `TransferResult`, và mọi DTO của API đều là record. Hai record bằng nhau khi mọi component bằng nhau, đúng với ý nghĩa của giá trị tiền.

**Không** nên dùng record khi:

- **Đối tượng có định danh và trạng thái thay đổi**, ví dụ JPA entity. Hibernate cần constructor không tham số, field ghi được và proxy bằng kế thừa. Hai entity có cùng dữ liệu vẫn có thể là hai dòng khác nhau.
- **Cần giấu dữ liệu.** Mọi component của record đều lộ ra qua accessor. Một class có trạng thái nội bộ (cache, khóa, kết nối) không hợp.
- **`equals` không nên dựa trên mọi field.** Record có field mảng thì `equals` so sánh tham chiếu, dễ sai.
- **Cần kế thừa** để chia sẻ trạng thái giữa các kiểu.

## 2. Sealed interface giúp gì so với enum hoặc class hierarchy mở?

Sealed interface khai báo **tập kiểu con đóng**: chỉ những kiểu được liệt kê (hoặc lồng bên trong) mới implement được. Nhờ vậy `switch` trên nó được compiler kiểm tra **đủ nhánh** mà không cần `default`.

- **So với enum:** hằng enum không mang dữ liệu riêng theo từng trường hợp. `TransferResult.InsufficientFunds` cần `walletId`, `available`, `requested`, còn `Completed` cần `TransferView`. Mỗi nhánh của sealed interface là một record với đúng những field nó cần.
- **So với hierarchy mở:** với interface thường, ai cũng thêm được implementation, nên `switch` buộc phải có `default`. Khi thêm kiểu mới, `default` nuốt mất nó mà không báo gì.
- **So với exception:** kết quả nghiệp vụ nằm ngay trong kiểu trả về của method, caller không thể quên xử lý. Exception dành cho lỗi lập trình (posting không cân bằng ném `IllegalArgumentException`).

Trong Ledgerly có hai tầng: `LedgerApi.post` trả `PostingResult`, `TransferService` đổi nó sang `TransferResult` bằng `switch`, rồi `TransferController` đổi `TransferResult` sang HTTP cũng bằng `switch`. Thêm một nhánh `Rejected` mới ở sổ cái thì `wallet` **không compile** cho tới khi có người quyết định nó thành mã lỗi nào. Đó là lỗi lúc biên dịch thay cho lỗi 500 lúc chạy.

## 3. `@Transactional` hoạt động qua proxy ra sao? Vì sao self-invocation làm mất transaction?

Spring không sửa class của mình. Khi tạo bean `LedgerService`, nó bọc bean trong một **proxy** (CGLIB sinh class con, hoặc JDK dynamic proxy nếu inject qua interface) và đưa proxy đó cho mọi nơi inject `LedgerApi`. Lời gọi `ledger.post(...)` từ `TransferService` đi vào proxy trước:

1. `TransactionInterceptor` đọc thuộc tính của `@Transactional`, xin `PlatformTransactionManager` một transaction: lấy connection từ pool, `setAutoCommit(false)`, gắn connection vào thread hiện tại.
2. Proxy gọi method thật. `JdbcClient` bên trong lấy đúng connection đã gắn vào thread, nên mọi câu SQL chạy chung một transaction.
3. Method trả về thì commit. Ném `RuntimeException` hoặc `Error` thì rollback. Checked exception mặc định **không** rollback.

**Self-invocation:** bên trong `LedgerService`, lời gọi `this.write(...)` đi thẳng tới đối tượng thật, **không qua proxy**, nên annotation trên method được gọi không có tác dụng. Nếu `post()` không có `@Transactional` mà gọi `this.write()` có `@Transactional`, sẽ không có transaction nào: mỗi câu SQL tự commit, và lỗi ở câu thứ ba để lại sổ cái ghi dở. Cũng vì proxy mà `@Transactional` trên method `private` không có tác dụng.

Trong Ledgerly: `post()` là method public có `@Transactional` và được gọi từ module khác qua `LedgerApi`, còn `write()` là private và chạy **trong** transaction của `post()`. `ArchitectureTest` giữ `@Transactional` chỉ ở `..internal.application..`, nên ranh giới transaction luôn nằm ở một tầng.

Cách sửa khi thật sự cần transaction riêng cho method bên trong: tách nó sang bean khác, hoặc dùng `TransactionTemplate`.

## 4. Vì sao phân trang keyset tốt hơn offset cho lịch sử giao dịch?

`OFFSET n` buộc database **đọc rồi bỏ** n dòng đầu, nên trang càng sâu càng chậm: trang 1.000 với 50 dòng mỗi trang phải đọc 50.000 dòng. Keyset dùng giá trị của dòng cuối trang trước làm điểm bắt đầu:

```sql
SELECT ... FROM entries
WHERE account_id = :accountId AND id < :after
ORDER BY id DESC LIMIT :limit
```

Index `(account_id, id)` cho phép nhảy thẳng tới vị trí đó rồi đọc đúng `limit` dòng. **Mọi trang tốn như nhau**, dù ví có 100 hay 10 triệu entry.

Lý do thứ hai là **tính đúng khi dữ liệu thay đổi**. Lịch sử giao dịch xếp mới nhất trước và liên tục có dòng mới ở đầu. Với offset, một entry mới xuất hiện giữa hai lần gọi sẽ đẩy mọi dòng xuống một vị trí: trang 2 lặp lại dòng cuối của trang 1. Với keyset, `id < after` không phụ thuộc số dòng đứng trước, nên không lặp và không sót (`TransferApiIT.historyPagination`).

Cái giá: không nhảy được tới "trang 37", chỉ đi tiếp từ con trỏ, và cột sắp xếp phải **duy nhất và ổn định**. `entries.id` là identity tăng dần nên đạt cả hai. Với lịch sử ví thì người dùng chỉ cuộn tiếp, nên không mất gì.

Chi tiết ở API: server đọc `limit + 1` dòng để biết còn trang sau hay không mà không cần câu `COUNT`. Nếu còn, `nextCursor` là id của dòng cuối trong trang. Nếu không, `nextCursor` là `null`.
