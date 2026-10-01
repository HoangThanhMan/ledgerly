# Spike W01-08: khóa dòng và deadlock

- **Ngày chạy:** 2026-10-01, PostgreSQL 18.6 (`postgres:18-alpine` trong compose), `deadlock_timeout = 1s` (mặc định)
- **Script:** [`scripts/spikes/locking.sh`](../../scripts/spikes/locking.sh)
- **Issue:** #8 · **Dùng cho:** ADR-0002 (bằng chứng), ADR-0004 (tuần 4)

> **Ghi chú.** Script và lần chạy dưới đây do AI thực hiện (xem [ai-usage](../ai-usage.md)). Log là output thật, không sửa ngoài việc bỏ dòng trống. Mục tiêu 3 của tuần 1 là **tự tay** chạy, nên phần [Chạy tay](#chạy-tay) và [Kết luận](#kết-luận) là việc của bạn.

## Mục đích

Tự thấy deadlock xảy ra thế nào khi hai transaction khóa hai dòng theo thứ tự ngược nhau, và thấy khóa theo thứ tự `id` tăng dần chỉ làm các transaction **chờ nhau** chứ không deadlock. Đây là nền cho quyết định khóa có thứ tự ở [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời).

## Cách chạy

```bash
docker compose up -d postgres
scripts/spikes/locking.sh        # cả ba kịch bản, khoảng 15 giây
scripts/spikes/locking.sh 1      # chỉ kịch bản 1
```

Script tạo database tạm `spike` với bảng `accounts(id int PRIMARY KEY, balance bigint)` có hai dòng `id = 1, 2`, rồi chạy hai phiên `psql` song song (S1, S2) theo một kịch bản thời gian cố định. Một phiên thứ ba (OBS) chụp `pg_stat_activity` giữa chừng. Script xóa database khi kết thúc.

Đọc log: `>>` là lúc script **gửi** câu lệnh, các dòng còn lại là output của `psql`. Dòng `Time:` là thời gian câu lệnh chạy, gồm cả thời gian chờ khóa.

## Kịch bản 1: khóa ngược thứ tự → deadlock

S1 khóa `id = 1` rồi `id = 2`. S2 khóa `id = 2` rồi `id = 1`.

```text
== Kịch bản 1: khóa ngược thứ tự (S1: 1 rồi 2, S2: 2 rồi 1)
19:02:14.086 [S1] >> BEGIN;
19:02:14.088 [S1] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:02:14.163 [S2]    SET
19:02:14.163 [S1]    SET
19:02:14.166 [S2]    Timing is on.
19:02:14.166 [S1]    Timing is on.
19:02:14.168 [S1]    BEGIN
19:02:14.170 [S1]    Time: 0.015 ms
19:02:14.172 [S1]     id | balance
19:02:14.174 [S1]    ----+---------
19:02:14.176 [S1]      1 | 1000000
19:02:14.178 [S1]    (1 row)
19:02:14.180 [S1]
19:02:14.181 [S1]    Time: 0.866 ms
19:02:15.089 [S2] >> BEGIN;
19:02:15.091 [S2] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:15.092 [S2]    BEGIN
19:02:15.094 [S2]    Time: 0.129 ms
19:02:15.096 [S2]     id | balance
19:02:15.098 [S2]    ----+---------
19:02:15.100 [S2]      2 | 1000000
19:02:15.102 [S2]    (1 row)
19:02:15.104 [S2]
19:02:15.106 [S2]    Time: 1.075 ms
19:02:16.094 [S1] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:16.489 [OBS] >> pg_stat_activity
19:02:16.497 [OBS]     phien | wait_event_type |  wait_event   | bi_chan_boi |                      query
19:02:16.497 [OBS]    -------+-----------------+---------------+-------------+-------------------------------------------------
19:02:16.497 [OBS]     S1    | Lock            | transactionid | {S2}        | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:16.497 [OBS]     S2    | Client          | ClientRead    |             | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:16.497 [OBS]    (2 rows)
19:02:16.497 [OBS]
19:02:16.797 [S2] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:02:17.104 [S1]    Time: 1001.057 ms (00:01.001)
19:02:17.104 [S2]     id | balance
19:02:17.112 [S1]    ERROR:  deadlock detected
19:02:17.112 [S2]    ----+---------
19:02:17.118 [S1]    DETAIL:  Process 696 waits for ShareLock on transaction 810; blocked by process 697.
19:02:17.119 [S2]      1 | 1000000
19:02:17.125 [S1]    Process 697 waits for ShareLock on transaction 809; blocked by process 696.
19:02:17.126 [S2]    (1 row)
19:02:17.129 [S1]    HINT:  See server log for query details.
19:02:17.130 [S2]
19:02:17.133 [S1]    CONTEXT:  while locking tuple (0,2) in relation "accounts"
19:02:17.133 [S2]    Time: 298.584 ms
19:02:18.106 [S1] >> COMMIT;
19:02:18.109 [S1]    ROLLBACK
19:02:18.112 [S1]    Time: 0.088 ms
19:02:18.610 [S2] >> COMMIT;
19:02:18.629 [S2]    COMMIT
19:02:18.636 [S2]    Time: 10.788 ms
```

Log phía server (`docker compose logs postgres`), có thêm câu lệnh của cả hai phiên:

```text
2026-10-01 12:02:17.102 UTC [696] ERROR:  deadlock detected
2026-10-01 12:02:17.102 UTC [696] DETAIL:  Process 696 waits for ShareLock on transaction 810; blocked by process 697.
	Process 697 waits for ShareLock on transaction 809; blocked by process 696.
	Process 696: SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
	Process 697: SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
2026-10-01 12:02:17.102 UTC [696] HINT:  See server log for query details.
2026-10-01 12:02:17.102 UTC [696] CONTEXT:  while locking tuple (0,2) in relation "accounts"
2026-10-01 12:02:17.102 UTC [696] STATEMENT:  SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
```

## Kịch bản 2: cùng thứ tự tăng dần → chỉ chờ

Cả S1 và S2 đều khóa `id = 1` rồi `id = 2`.

```text
== Kịch bản 2: cùng thứ tự id tăng dần (S1 và S2: 1 rồi 2)
19:02:18.999 [S1] >> BEGIN;
19:02:19.002 [S1] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:02:19.076 [S2]    SET
19:02:19.076 [S1]    SET
19:02:19.079 [S2]    Timing is on.
19:02:19.079 [S1]    Timing is on.
19:02:19.081 [S1]    BEGIN
19:02:19.083 [S1]    Time: 0.017 ms
19:02:19.085 [S1]     id | balance
19:02:19.087 [S1]    ----+---------
19:02:19.089 [S1]      1 | 1000000
19:02:19.090 [S1]    (1 row)
19:02:19.092 [S1]
19:02:19.094 [S1]    Time: 0.801 ms
19:02:20.002 [S2] >> BEGIN;
19:02:20.004 [S2] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:02:20.004 [S2]    BEGIN
19:02:20.007 [S2] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:20.007 [S2]    Time: 0.061 ms
19:02:20.009 [S2] >> COMMIT;
19:02:21.002 [OBS] >> pg_stat_activity
19:02:21.007 [S1] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:21.014 [S1]     id | balance
19:02:21.018 [S1]    ----+---------
19:02:21.021 [S1]      2 | 1000000
19:02:21.023 [S1]    (1 row)
19:02:21.025 [S1]
19:02:21.027 [S1]    Time: 0.505 ms
19:02:21.011 [OBS]     phien | wait_event_type |  wait_event   | bi_chan_boi |                      query
19:02:21.011 [OBS]    -------+-----------------+---------------+-------------+-------------------------------------------------
19:02:21.011 [OBS]     S1    | Client          | ClientRead    |             | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:02:21.011 [OBS]     S2    | Lock            | transactionid | {S1}        | SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:02:21.011 [OBS]    (2 rows)
19:02:21.011 [OBS]
19:02:22.019 [S1] >> COMMIT;
19:02:22.037 [S1]    COMMIT
19:02:22.037 [S2]     id | balance
19:02:22.041 [S1]    Time: 10.960 ms
19:02:22.042 [S2]    ----+---------
19:02:22.044 [S2]      1 | 1000000
19:02:22.046 [S2]    (1 row)
19:02:22.049 [S2]
19:02:22.052 [S2]    Time: 2029.348 ms (00:02.029)
19:02:22.053 [S2]     id | balance
19:02:22.056 [S2]    ----+---------
19:02:22.058 [S2]      2 | 1000000
19:02:22.060 [S2]    (1 row)
19:02:22.062 [S2]
19:02:22.064 [S2]    Time: 0.307 ms
19:02:22.066 [S2]    COMMIT
19:02:22.068 [S2]    Time: 0.440 ms
```

## Kịch bản 3: một câu lệnh, `ORDER BY id FOR UPDATE`

Giống luồng 6.1: khóa cả hai account trong một câu. S2 viết danh sách `IN (2, 1)` theo thứ tự ngược.

```text
== Kịch bản 3: một câu lệnh khóa cả hai dòng, ORDER BY id (như luồng 6.1)
                           QUERY PLAN
-----------------------------------------------------------------
 LockRows
   ->  Sort
         Sort Key: id
         ->  Bitmap Heap Scan on accounts
               Recheck Cond: (id = ANY ('{2,1}'::integer[]))
               ->  Bitmap Index Scan on accounts_pkey
                     Index Cond: (id = ANY ('{2,1}'::integer[]))
(7 rows)

19:02:22.523 [S1] >> BEGIN;
19:02:22.525 [S1] >> SELECT * FROM accounts WHERE id IN (1, 2) ORDER BY id FOR UPDATE;
19:02:22.601 [S1]    SET
19:02:22.601 [S2]    SET
19:02:22.605 [S2]    Timing is on.
19:02:22.605 [S1]    Timing is on.
19:02:22.607 [S1]    BEGIN
19:02:22.609 [S1]    Time: 0.027 ms
19:02:22.611 [S1]     id | balance
19:02:22.613 [S1]    ----+---------
19:02:22.615 [S1]      1 | 1000000
19:02:22.617 [S1]      2 | 1000000
19:02:22.619 [S1]    (2 rows)
19:02:22.621 [S1]
19:02:22.623 [S1]    Time: 1.143 ms
19:02:23.526 [S2] >> BEGIN;
19:02:23.533 [S2] >> SELECT * FROM accounts WHERE id IN (2, 1) ORDER BY id FOR UPDATE;
19:02:23.534 [S2]    BEGIN
19:02:23.540 [S2] >> COMMIT;
19:02:23.541 [S2]    Time: 0.158 ms
19:02:24.531 [S1] >> COMMIT;
19:02:24.549 [S1]    COMMIT
19:02:24.549 [S2]     id | balance
19:02:24.557 [S1]    Time: 10.672 ms
19:02:24.558 [S2]    ----+---------
19:02:24.564 [S2]      1 | 1000000
19:02:24.566 [S2]      2 | 1000000
19:02:24.570 [S2]    (2 rows)
19:02:24.572 [S2]
19:02:24.574 [S2]    Time: 1008.809 ms (00:01.009)
19:02:24.576 [S2]    COMMIT
19:02:24.579 [S2]    Time: 0.671 ms
```

## Dữ kiện quan sát được

| # | Dữ kiện | Ở đâu trong log |
|:-:|---|---|
| 1 | Khi S1 chờ dòng `id = 2` mà S2 đang giữ, `pg_stat_activity` báo `wait_event_type = Lock`, `wait_event = transactionid`, bị chặn bởi S2. Thông báo lỗi cũng ghi *"waits for ShareLock on **transaction** 810"*, chứ không ghi là chờ một dòng. | KB1, dòng OBS và DETAIL |
| 2 | S2 đang giữ khóa nhưng ở trạng thái `ClientRead` (đang chờ client gửi lệnh tiếp): một transaction rảnh vẫn giữ khóa dòng. | KB1, dòng OBS |
| 3 | S1 bắt đầu chờ lúc 16.094 và nhận `deadlock detected` sau 1001 ms, bằng `deadlock_timeout`. S2 tạo vòng chờ lúc 16.797, tức 0,3 giây trước khi lỗi xuất hiện. | KB1 |
| 4 | Nạn nhân là S1, phiên chờ trước nên hết `deadlock_timeout` trước và chạy bộ phát hiện deadlock. S2 lấy được dòng ngay sau đó (`Time: 298.584 ms`). | KB1 |
| 5 | Sau lỗi, `COMMIT` của S1 trả về `ROLLBACK`: toàn bộ transaction của S1 bị hủy, kể cả khóa trên `id = 1` mà nó đã giữ thành công. | KB1, dòng `>> COMMIT` của S1 |
| 6 | Cùng thứ tự khóa: S2 chờ `id = 1` mất 2029 ms (đến khi S1 commit) rồi chạy tiếp, không có lỗi. | KB2 |
| 7 | Kế hoạch thực thi có `LockRows` nằm **trên** `Sort`: các dòng được khóa theo thứ tự đã sắp xếp, không phụ thuộc thứ tự viết trong `IN (...)`. S2 chờ 1009 ms và không deadlock. | KB3, `EXPLAIN` |

## Chạy tay

✍️ *Việc của bạn: mở hai terminal và gõ từng lệnh để cảm nhận thời điểm bị chặn.*

```bash
# chuẩn bị (terminal bất kỳ)
docker compose exec -T postgres psql -U ledgerly -d ledger -c 'CREATE DATABASE spike;'
docker compose exec -T postgres psql -U ledgerly -d spike -c "CREATE TABLE accounts (id int PRIMARY KEY, balance bigint NOT NULL); INSERT INTO accounts VALUES (1, 1000000), (2, 1000000);"

# terminal 1 và terminal 2
docker compose exec postgres psql -U ledgerly -d spike

# dọn dẹp
docker compose exec -T postgres psql -U ledgerly -d ledger -c 'DROP DATABASE spike;'
```

- [ ] Tái hiện kịch bản 1 bằng tay, chụp màn hình hoặc dán log vào đây
- [ ] Thử thêm: đổi `SELECT ... FOR UPDATE` thành `UPDATE accounts SET balance = balance - 1 WHERE id = ...` (giống luồng chuyển tiền thật). Có deadlock không?

## Kết luận

✍️ *Bạn viết. Gợi ý câu hỏi cần trả lời được:*

- Vì sao PostgreSQL báo chờ *transaction* chứ không phải chờ *dòng*?
- PostgreSQL chọn nạn nhân thế nào? Ứng dụng phải làm gì khi nhận `40P01`?
- Vì sao khóa theo một thứ tự toàn cục loại bỏ được vòng chờ?
- `deadlock_timeout` lớn hay nhỏ thì được gì, mất gì? Khác gì `lock_timeout` (W04-02)?
- Kịch bản 3 đảm bảo được gì, và **không** đảm bảo được gì nếu sau này khóa account ở hai câu lệnh riêng?
