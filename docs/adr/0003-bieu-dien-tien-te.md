# ADR-0003: Biểu diễn tiền tệ và quy ước dấu của bút toán

- **Trạng thái:** Proposed
- **Ngày:** 2026-10-01 (dựng khung). Cập nhật ngày khi chuyển sang Accepted.
- **Tuần:** 1
- **Liên quan:** [01-kien-truc §5.1](../01-kien-truc.md#51-quy-ước), [§8.1](../01-kien-truc.md#81-quy-ước-chung), ADR-0002, issue #3

> **Ghi chú về khung này.** Khung và phần **Bằng chứng** do AI dựng (xem [ai-usage](../ai-usage.md)). Mọi output trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-01. Các phần có dấu ✍️ để trống cho bạn viết bằng lời của mình, theo [quy ước §7.3](../05-quy-uoc-lam-viec.md#7-chính-sách-dùng-ai). Xóa ghi chú này và các gợi ý ✍️ khi chuyển sang *Accepted*.

## Bối cảnh

✍️ *Bạn viết, trung lập, chưa nói tới giải pháp.* Gợi ý các lực cần nêu:

- Tiền phải chính xác tuyệt đối, và ba bất biến I1, I3, I4 ([chiến lược kiểm thử](../04-chien-luoc-kiem-thu.md#2-bất-biến-hệ-thống)) được kiểm tra bằng phép cộng.
- VND có 0 chữ số thập phân, nhưng mô hình cần mở rộng được sang tiền tệ khác (xem B5).
- Dải giá trị cần hỗ trợ: số dư lớn nhất của một ví, của account hệ thống sau nhiều năm cộng dồn.
- Client có thể là JavaScript (xem B4).
- Mapping Java ↔ PostgreSQL ↔ JSON phải đơn giản để không sinh bug chuyển đổi.

## Các phương án đã cân nhắc

✍️ *Bạn điền ưu và nhược điểm. Có thể thêm hoặc bỏ phương án.*

### Câu hỏi 1: Kiểu dữ liệu của số tiền

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. `double` / `DOUBLE PRECISION` | | |
| B. `BigDecimal` / `NUMERIC(p, s)` | | |
| C. `long` theo đơn vị nhỏ nhất / `BIGINT`, kèm `currency` | | |
| D. Thư viện JSR 354 (Moneta) | | |

Câu cần trả lời: vì sao không dùng `double` (B1)? Vì sao chọn `long` thay vì `BigDecimal`, xét hiệu năng, mapping với DB và giới hạn tràn số (B2, B3)?

### Câu hỏi 2: Dấu của entry

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Một cột `amount` có dấu (âm: tiền ra, dương: tiền vào) | | |
| B. Hai cột `debit` / `credit` không âm | | |
| C. Cột `amount` dương kèm cột `direction` (`DEBIT` / `CREDIT`) | | |

Câu cần trả lời: mỗi cách viết bất biến "tổng bằng 0" thế nào (B6)? Ràng buộc DB nào chặn được dữ liệu sai, ví dụ một dòng có cả debit lẫn credit? Trong kế toán, debit/credit tăng hay giảm số dư tùy loại account. Ví người dùng là *nợ phải trả* của nền tảng. Cách nào ít gây nhầm nhất khi đọc code?

### Câu hỏi 3: Số tiền trong JSON của API

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Số JSON: `"amount": 150000` | | |
| B. Chuỗi số nguyên: `"amount": "150000"` | | |
| C. Object: `"amount": { "value": "150000", "currency": "VND" }` | | |

Câu cần trả lời: vì sao API trả số tiền dạng **chuỗi** (B4)?

## Quyết định

✍️ *Bạn viết một đoạn, thể chủ động: "Chúng tôi sẽ ...". Trả lời cả ba câu hỏi trên.*

## Hệ quả

✍️ *Bạn viết.*

- **Tích cực:**
- **Tiêu cực / đánh đổi:**
- **Cần theo dõi:** gợi ý: tiền tệ có 2–3 chữ số thập phân (B5), phép chia (phí theo phần trăm, chia đều) và quy tắc làm tròn, tràn số khi cộng dồn số dư account hệ thống.

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
