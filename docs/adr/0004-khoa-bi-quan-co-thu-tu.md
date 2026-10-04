# ADR-0004: Khóa bi quan có thứ tự ở READ COMMITTED

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-04
- **Tuần:** 4
- **Liên quan:** [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời), ADR-0002, issue #28, #29, #32, #35, [spike khóa tuần 1](../journal/spike-locking.md)

> ADR này do AI soạn theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Mọi số liệu trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-04 trên máy dev (16 luồng CPU, 7 GB RAM, PostgreSQL 18 trong Testcontainers). Phần so sánh với SERIALIZABLE và khóa lạc quan là **lập luận**, chưa đo.

## Bối cảnh

Một posting đọc số dư của các account, kiểm tra quy tắc (`PostingRules`), rồi ghi entry và số dư mới. Code tuần 3 làm ba bước đó trong một transaction READ COMMITTED mà **không khóa gì**. Ba test của tuần 4 cho thấy hậu quả (B1):

- **Chi tiêu trùng.** 500 luồng cùng chuyển 1 đồng từ một ví có 100 đồng: cả 500 lần đều thành công. Mọi luồng đọc cùng một số dư, và `UPDATE accounts SET balance = :balanceAfter` ghi đè lên nhau (lost update).
- **Deadlock.** A→B và B→A chạy cùng lúc cập nhật hai dòng theo thứ tự ngược nhau.
- **Chờ vô hạn.** Một transaction giữ khóa dòng lâu (hoặc treo) làm mọi posting trên account đó đứng chờ mà không có giới hạn.

Các lực tác động:

- **Đúng trước, nhanh sau.** Bất biến I1–I4 phải đúng với mọi cách xen kẽ của các luồng.
- **Lỗi nghiệp vụ phải rõ.** Thiếu tiền là `InsufficientFunds`, không phải một exception của database mà client phải đoán.
- **Hot account.** `system:funding` và sau này `system:bank-settlement` nằm trong rất nhiều giao dịch.
- **Pool nhỏ.** HikariCP mặc định 10 connection. Một transaction chờ khóa là một connection bị giữ.
- **Quy tắc ở Java.** `PostingRules` là Java thuần và đã có test. Giải pháp nên để nó quyết định trên số dư **đã ổn định**.

## Các phương án đã cân nhắc

### Câu hỏi 1: Cơ chế chống ghi đè

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Khóa bi quan trên dòng account (`SELECT ... FOR UPDATE`), READ COMMITTED | Posting trên cùng account chạy lần lượt, số dư đọc sau khi khóa luôn là số dư mới nhất. Không có lỗi phải retry khi thứ tự khóa đúng. Từ chối vì thiếu tiền là quyết định của `PostingRules` | Giữ khóa suốt transaction: hot account thành hàng đợi. Cần kỷ luật về thứ tự khóa |
| B. SERIALIZABLE (SSI) | Không cần tự nghĩ khóa: database phát hiện mọi bất thường | PostgreSQL hủy một trong các transaction xung đột bằng `40001`, ứng dụng **phải** retry cả transaction. Với hot account, phần lớn transaction đồng thời xung đột với nhau nên tỉ lệ hủy cao. Kết quả trả về trước `COMMIT` chưa chắc đúng |
| C. Khóa lạc quan (cột `version`, `UPDATE ... WHERE version = ?`) | Không giữ khóa trong lúc tính toán. Tốt khi xung đột hiếm | Xung đột thành retry ở ứng dụng. Trên hot account, N luồng cùng đọc một `version` thì chỉ 1 thắng, N − 1 làm lại từ đầu: công việc bỏ đi tăng theo mức tranh chấp. Cập nhật hai account vẫn có thể deadlock nếu không có thứ tự |
| D. `UPDATE` có điều kiện (`SET balance = balance - x WHERE balance >= x`) | Một câu lệnh, nguyên tử, không cần `SELECT` trước | Quy tắc nghiệp vụ chuyển vào SQL, `PostingRules` mất vai trò. Phải suy ngược lý do từ số dòng bị ảnh hưởng. Vẫn cần thứ tự cập nhật để tránh deadlock |

### Câu hỏi 2: Lấy khóa theo cách nào

Đo bằng `DeadlockFreedomIT` (1.000 cặp A→B và B→A, tức 2.000 lần chuyển, cùng xuất phát), mỗi cách 3 lượt (B1):

| Cách khóa | Hoàn tất | Deadlock `40P01` | Không mở được transaction | Thời gian |
|---|:-:|:-:|:-:|:-:|
| 1. Không khóa dòng (code tuần 3) | 27 / 15 / 25 | 74 / 81 / 85 | 1.899 / 1.904 / 1.890 | 75 / 82 / 86 giây |
| 2. Một câu `FOR NO KEY UPDATE`, **bỏ `ORDER BY`** | 306 / 316 / 344 | 74 / 72 / 72 | 1.620 / 1.612 / 1.584 | 70 / 70 / 62 giây |
| 3. Mỗi account một câu, theo thứ tự của posting (nguồn trước, đích sau) | 22 / 19 / 20 | 143 / 137 / 129 | 1.835 / 1.844 / 1.851 | 84 / 83 / 71 giây |
| 4. Một câu `ORDER BY id FOR NO KEY UPDATE` | **2.000 / 2.000 / 2.000** | **0 / 0 / 0** | 0 / 0 / 0 | 2,6 / 2,6 / 2,7 giây |

Cột "không mở được transaction" là `CannotCreateTransactionException`. Thông điệp của exception chưa được kiểm tra. Cách hiểu hợp lý nhất: mỗi deadlock mất 1 giây (`deadlock_timeout`) mới được phát hiện, trong lúc đó các connection của pool bị giữ, và các luồng còn lại hết 30 giây chờ connection của HikariCP.

### Câu hỏi 3: Mức khóa

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| `FOR UPDATE` | Quen thuộc, mạnh nhất | Xung đột với cả `FOR KEY SHARE`, là khóa mà mọi `INSERT` có khóa ngoại tới `accounts` phải lấy. Một posting đang giữ `system:funding` sẽ chặn cả việc thêm dòng tham chiếu tới nó |
| `FOR NO KEY UPDATE` | Đúng bằng khóa mà câu `UPDATE accounts SET balance` tự lấy (không đổi khóa chính). Hai posting vẫn loại trừ nhau. Không chặn `FOR KEY SHARE` | Ít người biết hơn |

### Câu hỏi 4: Chờ khóa bao lâu

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Không giới hạn (mặc định) | Không cần xử lý lỗi | Một transaction treo kéo theo mọi posting cùng account, tới khi cạn pool |
| `NOWAIT` | Không bao giờ chờ | Hot account sẽ từ chối gần như mọi request đồng thời |
| `lock_timeout` cho riêng transaction | Chờ được hàng đợi ngắn, nhưng có trần | Thêm một round trip để đặt tham số. Phải xử lý lỗi `55P03` |

## Quyết định

Chúng tôi sẽ dùng **khóa bi quan trên dòng account ở READ COMMITTED** (phương án 1A). Mỗi posting, trước khi đọc số dư, khóa **mọi** account của nó bằng **một câu duy nhất**:

```sql
SELECT id, currency, balance, allow_negative FROM accounts
WHERE id = ANY (:ids) ORDER BY id FOR NO KEY UPDATE
```

`ORDER BY id` là bắt buộc (cách 4 ở câu hỏi 2). Mức khóa là `FOR NO KEY UPDATE`. Trước câu khóa, posting đặt `lock_timeout` cho riêng transaction (`set_config('lock_timeout', ..., true)`, mặc định **2 giây**, cấu hình bằng `ledgerly.ledger.lock-timeout`). Hết hạn thì trả `503 /problems/overloaded` kèm `Retry-After: 1`. Nạn nhân của deadlock, nếu có, nhận cùng câu trả lời. Mọi đường ghi vào `accounts.balance` phải đi qua `AccountRepository.lockAll`.

SERIALIZABLE và khóa lạc quan bị loại cho đường ghi chính vì cả hai biến tranh chấp thành retry, và hệ thống này có hot account theo thiết kế. Chúng sẽ được **đo** cùng khóa bi quan ở tuần 11 (`LockStrategyIT`, ADR-0010) thay vì chỉ lập luận.

## Hệ quả

- **Tích cực:** chi tiêu trùng và deadlock bị loại bằng cấu trúc, không dựa vào may mắn của lịch chạy. `PostingRules` quyết định trên số dư đã khóa, nên lỗi thiếu tiền vẫn là kết quả nghiệp vụ. Không có vòng retry trong code ứng dụng. Thời gian chờ khóa có trần và đo được (`ledgerly.posting.lock.wait`).
- **Tiêu cực / đánh đổi:** các posting chạm cùng account chạy **tuần tự**. Thông lượng trên hot account bị giới hạn bởi thời gian một transaction giữ khóa. Mỗi posting thêm hai câu lệnh (`set_config` và câu khóa). Khóa giữ tới hết transaction, nên **không được** gọi mạng trong transaction posting.
- **Một câu không có `ORDER BY` là không đủ.** Cách 2 vẫn deadlock khoảng 72 lần trên 2.000 lần chuyển. Kế hoạch của câu lệnh là Bitmap Heap Scan (B2), tức khóa theo vị trí vật lý của dòng. Mỗi `UPDATE` ghi phiên bản mới của dòng vào vị trí mới, nên thứ tự vật lý của hai account đổi theo thời gian. Đây là cách giải thích khớp với kế hoạch quan sát được, chưa được chứng minh trực tiếp.
- **Spring không dịch `55P03`.** Lỗi hết `lock_timeout` ra `UncategorizedSQLException`, không phải `CannotAcquireLockException`. `AccountRepository` tự đổi (B4).
- **Cần theo dõi:** p99 của `ledgerly.posting.lock.wait` và tỉ lệ 503 khi tải tăng (tuần 7). Nếu hàng đợi trên `system:funding` thành nút cổ chai thì xem lại ở ADR-0010. Mọi bảng mới có khóa ngoại tới `accounts` hưởng lợi từ `FOR NO KEY UPDATE`; nếu có code đổi khóa chính của account thì phải dùng `FOR UPDATE`.

## Bằng chứng

**B1. Thí nghiệm W04-05 và các test.** Mỗi cách khóa ở câu hỏi 2 là một lần sửa tạm `AccountRepository.lockAll`, chạy `DeadlockFreedomIT` ba lượt, rồi hoàn nguyên. Trước khi có khóa (cách 1), trên cùng code:

| Test | Kết quả không khóa | Kết quả với quyết định này |
|---|---|---|
| `HotWalletDrainIT` (500 luồng, ví có 100) | 500 lần thành công | 100 thành công, 400 `InsufficientFunds` |
| `ConcurrentTransferIT` (10.000 lần chuyển, 10 ví, 200 virtual threads) | 126 exception deadlock | Xanh 10 lần liên tiếp, 4,6–5,7 giây mỗi lần, khoảng 8.000 hoàn tất và 2.000 thiếu tiền |
| `DeadlockFreedomIT` | 74–85 deadlock, 15–27 lần hoàn tất | 0 deadlock, 2.000 lần hoàn tất |
| `LockTimeoutIT` | Chờ quá 10 giây (test tự ngắt) | 503 sau khoảng 2 giây |

**B2. Kế hoạch của câu khóa** (PostgreSQL 18, database compose, 5 account):

```text
-- không có ORDER BY
LockRows
  ->  Bitmap Heap Scan on accounts
        Recheck Cond: (id = ANY ('{...}'::uuid[]))
        ->  Bitmap Index Scan on accounts_pkey

-- có ORDER BY id
LockRows
  ->  Sort
        Sort Key: id
        ->  Bitmap Heap Scan on accounts
```

Với `ORDER BY`, nút `LockRows` nằm **trên** `Sort`, nên dòng được khóa theo thứ tự `id`.

**B3. Vì sao thứ tự toàn cục loại deadlock.** Deadlock cần một vòng chờ: T1 giữ X chờ Y, T2 giữ Y chờ X. Nếu mọi transaction lấy khóa theo `id` tăng dần thì transaction đang giữ Y (với X < Y) đã phải lấy X trước đó hoặc không cần X. Không transaction nào vừa giữ khóa lớn hơn vừa chờ khóa nhỏ hơn, nên không có vòng. Spike tuần 1 đã thấy điều này bằng hai phiên `psql` ([spike-locking](../journal/spike-locking.md)).

**B4. `55P03` trong Spring Framework 7.** Log của `LockTimeoutIT` trước khi có bước dịch:

```text
org.springframework.jdbc.UncategorizedSQLException: ... SQL state [55P03]; error code [0];
ERROR: canceling statement due to lock timeout
  Where: while locking tuple (0,12) in relation "accounts"
```

**B5. Tài liệu.** PostgreSQL docs, *Explicit Locking* (bảng xung đột của khóa dòng, `deadlock_timeout`) và *Transaction Isolation* (SERIALIZABLE và `40001`), đã ghi chú ở [notes-postgresql-locking](../journal/notes-postgresql-locking.md).
