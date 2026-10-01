# ADR-0003: Biểu diễn tiền tệ và quy ước dấu của bút toán

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-01
- **Tuần:** 1
- **Liên quan:** [01-kien-truc §5.1](../01-kien-truc.md#51-quy-ước), [§8.1](../01-kien-truc.md#81-quy-ước-chung), ADR-0002, issue #3

> ADR này do AI soạn theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Mọi output trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-01.

## Bối cảnh

Ledgerly lưu và cộng trừ tiền ở ba nơi: Java (domain), PostgreSQL (số dư, entries, ràng buộc) và JSON (API). Cần chốt ba việc trước khi viết schema ở tuần 2: kiểu dữ liệu của số tiền, cách ghi dấu của một entry, và cách đưa số tiền ra API.

Các lực tác động:

- **Chính xác tuyệt đối.** Ba bất biến I1 (tổng entries của một giao dịch bằng 0), I3 (số dư khớp tổng entries) và I4 (tổng mọi số dư bằng 0) đều là phép cộng. Chỉ cần lệch 1 đồng là test bất biến đỏ.
- **Chỉ có VND trong phạm vi hiện tại.** VND có 0 chữ số thập phân theo ISO 4217 (B5), nhưng mô hình phải mở rộng được sang tiền tệ có 2 hoặc 3 chữ số thập phân mà không đổi schema.
- **Dải giá trị.** Một ví người dùng không bao giờ gần tới 10^15 đồng. Account hệ thống thì cộng dồn mãi, nên tràn số phải **báo lỗi** chứ không được quay vòng âm thầm.
- **Client JavaScript** đọc JSON bằng `Number`, kiểu chỉ chính xác tới 2^53 − 1 (B4).
- **Ít chỗ chuyển đổi.** Mỗi lần đổi kiểu giữa Java, JDBC và JSON là một chỗ có thể sinh bug.

## Các phương án đã cân nhắc

### Câu hỏi 1: Kiểu dữ liệu của số tiền

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. `double` / `DOUBLE PRECISION` | Nhanh, có sẵn | Không biểu diễn chính xác số thập phân: `0.1 + 0.2 != 0.3`, ép kiểu mất 1 cent (B1). Loại ngay |
| B. `BigDecimal` / `NUMERIC(p, s)` | Chính xác, có sẵn phần thập phân và quy tắc làm tròn | Mỗi giá trị là một object. `equals` so cả scale nên 2.0 khác 2.00 trong `HashSet` (B3). Constructor nhận `double` mang theo sai số. `NUMERIC` là kiểu độ dài biến đổi, so với `BIGINT` cố định 8 byte |
| C. `long` theo đơn vị nhỏ nhất / `BIGINT`, kèm `currency` | Chính xác. Kiểu nguyên thủy, không cấp phát object. Map thẳng sang `BIGINT`. Tràn số bắt được ở cả Java (`Math.addExact`) lẫn PostgreSQL (`bigint out of range`) (B2) | Toán tử `+` thường quay vòng âm thầm, nên phải luôn dùng `addExact`. Phép chia (phí theo %, chia đều) cần tự định nghĩa quy tắc làm tròn. Hiển thị phải đổi theo số chữ số thập phân của tiền tệ |
| D. Thư viện JSR 354 (Moneta) | Chuẩn hóa tiền tệ, định dạng, quy đổi tỉ giá | Thêm một dependency, và bên dưới vẫn là `BigDecimal`. Quá nặng cho một hệ thống chỉ có VND |

### Câu hỏi 2: Dấu của entry

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Một cột `amount` có dấu (âm: tiền ra, dương: tiền vào) | I1 là `SUM(amount) = 0`, I3 là `balance = SUM(amount)`, một biểu thức cho mỗi bất biến (B6). Chỉ cần một ràng buộc `CHECK (amount <> 0)`. Dấu nhìn từ phía account nên khớp trực tiếp với số dư | Không dùng thuật ngữ debit/credit của kế toán. Báo cáo kế toán phải suy ra debit/credit |
| B. Hai cột `debit` / `credit` không âm | Đúng mẫu sổ sách kế toán truyền thống | Có trạng thái sai biểu diễn được (cả hai khác 0, hoặc cả hai bằng 0), nên cần thêm `CHECK`. Bất biến phải viết `SUM(debit) - SUM(credit)` |
| C. `amount` dương kèm `direction` (`DEBIT` / `CREDIT`) | Giống Modern Treasury. Rõ ràng với người làm kế toán | Debit tăng hay giảm số dư **tùy loại account**: ví người dùng là nợ phải trả của nền tảng nên debit làm giảm ví. Mỗi lần tính số dư phải tra loại account, dễ nhầm khi đọc code |

### Câu hỏi 3: Số tiền trong JSON của API

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Số JSON: `"amount": 150000` | Gọn, dễ đọc | `JSON.parse` làm tròn số lớn hơn 2^53 mà **không báo lỗi** (B4) |
| B. Chuỗi số nguyên theo đơn vị nhỏ nhất, kèm trường `currency` | Không mất chính xác ở bất kỳ client nào. Server validate chặt được định dạng | Client phải tự đổi kiểu (`BigInt` hoặc thư viện decimal) nếu muốn tính toán |
| C. Object: `"amount": { "value": "150000", "currency": "VND" }` | Không thể quên tiền tệ | Dài dòng hơn B, và khác với ví dụ API đã có ở [01-kien-truc §8.4](../01-kien-truc.md#84-ví-dụ) |

## Quyết định

Chúng tôi sẽ biểu diễn tiền bằng **`long` theo đơn vị nhỏ nhất của tiền tệ, kèm mã tiền tệ ISO 4217** (phương án 1C). Trong Java đó là `record Money(long amount, Currency currency)` ở module `shared`. Mọi phép cộng trừ đi qua `Math.addExact` / `Math.subtractExact`. Cộng hai `Money` khác tiền tệ là lỗi lập trình và ném exception. Trong PostgreSQL, số tiền là `BIGINT`, còn `accounts.currency` là `CHAR(3)`. Cả codebase không có `double` hay `float` cho tiền.

Entry dùng **một cột `amount` có dấu, nhìn từ phía account** (phương án 2A): âm là tiền ra khỏi account, dương là tiền vào, và `CHECK (amount <> 0)`. Entry không lưu tiền tệ riêng, vì tiền tệ thuộc về account. `PostingRules` từ chối một giao dịch có entry thuộc các account khác tiền tệ, nên I1 luôn là tổng trong cùng một tiền tệ.

API trả và nhận số tiền dưới dạng **chuỗi số nguyên theo đơn vị nhỏ nhất, kèm trường `currency` riêng** (phương án 3B), ví dụ `"amount": "150000", "currency": "VND"`. Server chỉ chấp nhận chuỗi khớp `^[1-9][0-9]*$` cho số tiền đầu vào (không dấu, không số 0 ở đầu, không phần thập phân, không mũ), rồi `Long.parseLong`. Tràn số thì trả 400.

## Hệ quả

- **Tích cực:** phép tính tiền chính xác và tràn số luôn ném lỗi ở cả Java lẫn DB. Bất biến I1, I3, I4 viết được bằng một câu `SUM` đơn giản và dùng chung cho `InvariantChecker` và constraint trigger. Mapping Java ↔ JDBC là `long` ↔ `BIGINT`, không có bước chuyển đổi. Client nào đọc API cũng không mất chính xác.
- **Tiêu cực / đánh đổi:** dấu của entry không theo thuật ngữ debit/credit. Nếu cần báo cáo kế toán thì tạo view suy ra từ dấu và loại account. Phép chia và phần trăm chưa có quy tắc làm tròn. Client phải parse chuỗi.
- **Cần theo dõi:**
  - Khi thêm tiền tệ có phần thập phân (USD, KWD), API vẫn dùng đơn vị nhỏ nhất (cent, fils). Tài liệu API phải nói rõ điều đó, và hiển thị phải chia theo `Currency.getDefaultFractionDigits()`.
  - Khi có tính năng phí hoặc chia tiền, cần một ADR về quy tắc làm tròn (ví dụ `HALF_EVEN`, phần dư dồn vào account nào).
  - Thêm quy tắc ArchUnit ở W03-08 cấm field kiểu `double`/`float` trong `..domain..` để quyết định này được máy kiểm tra.

## Bằng chứng

Tất cả chạy ngày 2026-10-01 trên máy dev. Chạy lại được bằng các lệnh ở đầu mỗi mục.

### B1. `double` không biểu diễn chính xác số thập phân

JShell 25.0.4.1 (`~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/jshell`):

```text
jshell> 0.1 + 0.2
$1 ==> 0.30000000000000004

jshell> 0.1 + 0.2 == 0.3
$2 ==> false

jshell> 19.99 * 100
$3 ==> 1998.9999999999998

jshell> (long) (19.99 * 100)
$4 ==> 1998
```

`$4` cho thấy lỗi thật: đổi 19,99 USD sang cent bằng phép nhân `double` rồi ép kiểu thì mất 1 cent.

PostgreSQL 18.6 cũng vậy:

```text
SELECT 0.1::float8 + 0.2::float8 AS float8_sum, 0.1::numeric + 0.2::numeric AS numeric_sum;
     float8_sum      | numeric_sum
---------------------+-------------
 0.30000000000000004 |         0.3
```

### B2. Tràn số `long`: âm thầm so với báo lỗi

```text
jshell> Long.MAX_VALUE
$5 ==> 9223372036854775807

jshell> Long.MAX_VALUE + 1
$6 ==> -9223372036854775808

jshell> Math.addExact(Long.MAX_VALUE, 1L)
|  Exception java.lang.ArithmeticException: long overflow
|        at Math.addExact (Math.java:935)
|        at (#7:1)
```

PostgreSQL báo lỗi khi `BIGINT` tràn, không quay vòng như toán tử `+` của Java:

```text
SELECT 9223372036854775807::bigint + 1;
ERROR:  bigint out of range
```

### B3. Hai cái bẫy của `BigDecimal`

```text
jshell> new java.math.BigDecimal(0.1)
$8 ==> 0.1000000000000000055511151231257827021181583404541015625

jshell> new java.math.BigDecimal("2.0").equals(new java.math.BigDecimal("2.00"))
$9 ==> false

jshell> new java.math.BigDecimal("2.0").compareTo(new java.math.BigDecimal("2.00"))
$10 ==> 0

jshell> new java.util.HashSet<>(java.util.List.of(new java.math.BigDecimal("2.0"), new java.math.BigDecimal("2.00"))).size()
$11 ==> 2
```

`equals` so cả scale, nên 2.0 và 2.00 là hai phần tử khác nhau trong `HashSet` hoặc làm key của `HashMap`. Constructor nhận `double` mang theo sai số của `double`.

### B4. JavaScript `Number` chỉ chính xác tới 2^53 − 1

Node v20.20.2:

```text
> Number.MAX_SAFE_INTEGER
9007199254740991
> 2 ** 53 + 1
9007199254740992
> JSON.parse('{"amount": 9007199254740993}').amount
9007199254740992
> JSON.parse('{"amount": "9007199254740993"}').amount
'9007199254740993'
> BigInt(JSON.parse('{"amount": "9007199254740993"}').amount) + 1n
9007199254740994n
```

Số JSON lớn hơn 2^53 bị `JSON.parse` làm tròn **mà không báo lỗi**. Chuỗi thì giữ nguyên, client tự đổi sang `BigInt` nếu cần tính.

### B5. Số chữ số thập phân theo tiền tệ (ISO 4217)

```text
jshell> java.util.Currency.getInstance("VND").getDefaultFractionDigits()
$12 ==> 0

jshell> java.util.Currency.getInstance("USD").getDefaultFractionDigits()
$13 ==> 2

jshell> java.util.Currency.getInstance("KWD").getDefaultFractionDigits()
$14 ==> 3
```

### B6. Bất biến "tổng bằng 0" với hai cách lưu dấu

PostgreSQL 18.6, một giao dịch chuyển 150.000 VND từ ví A sang ví B. Ở cách B, quy ước trong ví dụ là debit làm giảm ví (vì ví là nợ phải trả của nền tảng).

```text
-- Cách A: một cột amount có dấu
WITH entries(tx, account, amount) AS (VALUES (1, 'wallet:A', -150000), (1, 'wallet:B', 150000))
SELECT tx, SUM(amount) AS tong FROM entries GROUP BY tx;
 tx | tong
----+------
  1 |    0

-- Cách B: hai cột debit/credit không âm
WITH entries(tx, account, debit, credit) AS (VALUES (1, 'wallet:A', 150000, 0), (1, 'wallet:B', 0, 150000))
SELECT tx, SUM(debit) AS tong_no, SUM(credit) AS tong_co, SUM(debit) - SUM(credit) AS chenh FROM entries GROUP BY tx;
 tx | tong_no | tong_co | chenh
----+---------+---------+-------
  1 |  150000 |  150000 |     0
```

### Tham khảo để đối chiếu

- Stripe API: số tiền là số nguyên theo đơn vị nhỏ nhất của tiền tệ (ví dụ cent).
- Modern Treasury Ledgers: entry có `amount` (số nguyên, đơn vị nhỏ nhất) và `direction` (`credit` / `debit`), tức phương án C của câu hỏi 2.
- ISO 4217: bảng số chữ số thập phân của từng tiền tệ.
