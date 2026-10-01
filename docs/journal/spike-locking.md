# Spike W01-08: khóa dòng và deadlock

- **Ngày chạy:** 2026-10-01, PostgreSQL 18.6 (`postgres:18-alpine` trong compose), `deadlock_timeout = 1s` (mặc định)
- **Script:** [`scripts/spikes/locking.sh`](../../scripts/spikes/locking.sh)
- **Issue:** #8 · **Dùng cho:** ADR-0002 (bằng chứng), ADR-0004 (tuần 4)

> Script, các lần chạy và phần kết luận do AI thực hiện theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Log là output thật của lần chạy cuối, không sửa gì ngoài việc bỏ dòng trống và khoảng trắng cuối dòng. Script đã chạy 5 lần (kịch bản 4 có từ lần thứ 4), lần nào cũng cho cùng kết quả.

## Mục đích

Tự thấy deadlock xảy ra thế nào khi hai transaction khóa hai dòng theo thứ tự ngược nhau, và thấy khóa theo thứ tự `id` tăng dần chỉ làm các transaction **chờ nhau** chứ không deadlock. Đây là nền cho quyết định khóa có thứ tự ở [01-kien-truc §7](../01-kien-truc.md#7-kiểm-soát-đồng-thời).

## Cách chạy

```bash
docker compose up -d postgres
scripts/spikes/locking.sh        # cả bốn kịch bản, khoảng 20 giây
scripts/spikes/locking.sh 1      # chỉ kịch bản 1
```

Script tạo database tạm `spike` với bảng `accounts(id int PRIMARY KEY, balance bigint)` có hai dòng `id = 1, 2`, mỗi dòng số dư 1.000.000. Sau đó nó chạy hai phiên `psql` song song (S1, S2) theo một kịch bản thời gian cố định. Một phiên thứ ba (OBS) chụp `pg_stat_activity` giữa chừng. Script xóa database khi kết thúc.

Đọc log: `>>` là lúc script **gửi** câu lệnh, các dòng còn lại là output của `psql`. Dòng `Time:` là thời gian câu lệnh chạy, gồm cả thời gian chờ khóa.

## Kịch bản 1: khóa ngược thứ tự → deadlock

S1 khóa `id = 1` rồi `id = 2`. S2 khóa `id = 2` rồi `id = 1`.

```text
== Kịch bản 1: khóa ngược thứ tự (S1: 1 rồi 2, S2: 2 rồi 1)
19:25:19.558 [S1] >> BEGIN;
19:25:19.560 [S1] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:25:19.630 [S1]    SET
19:25:19.630 [S2]    SET
19:25:19.633 [S1]    Timing is on.
19:25:19.633 [S2]    Timing is on.
19:25:19.635 [S1]    BEGIN
19:25:19.637 [S1]    Time: 0.025 ms
19:25:19.639 [S1]     id | balance
19:25:19.641 [S1]    ----+---------
19:25:19.642 [S1]      1 | 1000000
19:25:19.644 [S1]    (1 row)
19:25:19.646 [S1]
19:25:19.648 [S1]    Time: 0.852 ms
19:25:20.561 [S2] >> BEGIN;
19:25:20.568 [S2] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:20.569 [S2]    BEGIN
19:25:20.571 [S2]    Time: 0.088 ms
19:25:20.575 [S2]     id | balance
19:25:20.577 [S2]    ----+---------
19:25:20.578 [S2]      2 | 1000000
19:25:20.580 [S2]    (1 row)
19:25:20.582 [S2]
19:25:20.584 [S2]    Time: 0.884 ms
19:25:21.565 [S1] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:21.960 [OBS] >> pg_stat_activity
19:25:21.968 [OBS]     phien | wait_event_type |  wait_event   | bi_chan_boi |                      query
19:25:21.968 [OBS]    -------+-----------------+---------------+-------------+-------------------------------------------------
19:25:21.968 [OBS]     S1    | Lock            | transactionid | {S2}        | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:21.968 [OBS]     S2    | Client          | ClientRead    |             | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:21.968 [OBS]    (2 rows)
19:25:21.968 [OBS]
19:25:22.274 [S2] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:25:22.573 [S2]     id | balance
19:25:22.573 [S1]    Time: 1000.683 ms (00:01.001)
19:25:22.576 [S2]    ----+---------
19:25:22.576 [S1]    ERROR:  deadlock detected
19:25:22.578 [S1]    DETAIL:  Process 328 waits for ShareLock on transaction 852; blocked by process 327.
19:25:22.578 [S2]      1 | 1000000
19:25:22.580 [S1]    Process 327 waits for ShareLock on transaction 851; blocked by process 328.
19:25:22.580 [S2]    (1 row)
19:25:22.582 [S1]    HINT:  See server log for query details.
19:25:22.582 [S2]
19:25:22.584 [S2]    Time: 291.998 ms
19:25:22.584 [S1]    CONTEXT:  while locking tuple (0,2) in relation "accounts"
19:25:23.578 [S1] >> COMMIT;
19:25:23.585 [S1]    ROLLBACK
19:25:23.587 [S1]    Time: 0.164 ms
19:25:24.087 [S2] >> COMMIT;
19:25:24.094 [S2]    COMMIT
19:25:24.097 [S2]    Time: 0.698 ms
```

Log phía server (`docker compose logs postgres`), có thêm câu lệnh của cả hai phiên:

```text
2026-10-01 12:25:22.573 UTC [328] ERROR:  deadlock detected
2026-10-01 12:25:22.573 UTC [328] DETAIL:  Process 328 waits for ShareLock on transaction 852; blocked by process 327.
	Process 327 waits for ShareLock on transaction 851; blocked by process 328.
	Process 328: SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
	Process 327: SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
2026-10-01 12:25:22.573 UTC [328] HINT:  See server log for query details.
2026-10-01 12:25:22.573 UTC [328] CONTEXT:  while locking tuple (0,2) in relation "accounts"
2026-10-01 12:25:22.573 UTC [328] STATEMENT:  SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
```

## Kịch bản 2: cùng thứ tự tăng dần → chỉ chờ

Cả S1 và S2 đều khóa `id = 1` rồi `id = 2`.

```text
== Kịch bản 2: cùng thứ tự id tăng dần (S1 và S2: 1 rồi 2)
19:25:24.445 [S1] >> BEGIN;
19:25:24.447 [S1] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:25:24.518 [S2]    SET
19:25:24.518 [S1]    SET
19:25:24.521 [S2]    Timing is on.
19:25:24.521 [S1]    Timing is on.
19:25:24.523 [S1]    BEGIN
19:25:24.525 [S1]    Time: 0.014 ms
19:25:24.527 [S1]     id | balance
19:25:24.528 [S1]    ----+---------
19:25:24.530 [S1]      1 | 1000000
19:25:24.532 [S1]    (1 row)
19:25:24.534 [S1]
19:25:24.536 [S1]    Time: 0.824 ms
19:25:25.449 [S2] >> BEGIN;
19:25:25.455 [S2] >> SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:25:25.455 [S2]    BEGIN
19:25:25.457 [S2] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:25.457 [S2]    Time: 0.070 ms
19:25:25.459 [S2] >> COMMIT;
19:25:26.447 [OBS] >> pg_stat_activity
19:25:26.452 [S1] >> SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:26.456 [S1]     id | balance
19:25:26.458 [S1]    ----+---------
19:25:26.460 [S1]      2 | 1000000
19:25:26.462 [S1]    (1 row)
19:25:26.464 [S1]
19:25:26.466 [S1]    Time: 0.316 ms
19:25:26.453 [OBS]     phien | wait_event_type |  wait_event   | bi_chan_boi |                      query
19:25:26.453 [OBS]    -------+-----------------+---------------+-------------+-------------------------------------------------
19:25:26.453 [OBS]     S1    | Client          | ClientRead    |             | SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
19:25:26.453 [OBS]     S2    | Lock            | transactionid | {S1}        | SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
19:25:26.453 [OBS]    (2 rows)
19:25:26.453 [OBS]
19:25:27.459 [S1] >> COMMIT;
19:25:27.465 [S2]     id | balance
19:25:27.465 [S1]    COMMIT
19:25:27.468 [S2]    ----+---------
19:25:27.470 [S1]    Time: 0.632 ms
19:25:27.470 [S2]      1 | 1000000
19:25:27.474 [S2]    (1 row)
19:25:27.476 [S2]
19:25:27.478 [S2]    Time: 2008.018 ms (00:02.008)
19:25:27.479 [S2]     id | balance
19:25:27.481 [S2]    ----+---------
19:25:27.483 [S2]      2 | 1000000
19:25:27.485 [S2]    (1 row)
19:25:27.487 [S2]
19:25:27.489 [S2]    Time: 0.189 ms
19:25:27.491 [S2]    COMMIT
19:25:27.493 [S2]    Time: 0.499 ms
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

19:25:27.920 [S1] >> BEGIN;
19:25:27.923 [S1] >> SELECT * FROM accounts WHERE id IN (1, 2) ORDER BY id FOR UPDATE;
19:25:27.994 [S1]    SET
19:25:27.994 [S2]    SET
19:25:27.997 [S2]    Timing is on.
19:25:27.996 [S1]    Timing is on.
19:25:27.999 [S1]    BEGIN
19:25:28.001 [S1]    Time: 0.015 ms
19:25:28.003 [S1]     id | balance
19:25:28.004 [S1]    ----+---------
19:25:28.007 [S1]      1 | 1000000
19:25:28.008 [S1]      2 | 1000000
19:25:28.010 [S1]    (2 rows)
19:25:28.012 [S1]
19:25:28.014 [S1]    Time: 1.099 ms
19:25:28.924 [S2] >> BEGIN;
19:25:28.931 [S2] >> SELECT * FROM accounts WHERE id IN (2, 1) ORDER BY id FOR UPDATE;
19:25:28.932 [S2]    BEGIN
19:25:28.933 [S2] >> COMMIT;
19:25:28.934 [S2]    Time: 0.058 ms
19:25:29.928 [S1] >> COMMIT;
19:25:29.936 [S1]    COMMIT
19:25:29.936 [S2]     id | balance
19:25:29.938 [S1]    Time: 0.612 ms
19:25:29.938 [S2]    ----+---------
19:25:29.941 [S2]      1 | 1000000
19:25:29.947 [S2]      2 | 1000000
19:25:29.953 [S2]    (2 rows)
19:25:29.959 [S2]
19:25:29.964 [S2]    Time: 1002.182 ms (00:01.002)
19:25:29.966 [S2]    COMMIT
19:25:29.968 [S2]    Time: 0.359 ms
```

## Kịch bản 4: chuyển tiền bằng `UPDATE`, ngược chiều nhau

Không có `SELECT ... FOR UPDATE`, chỉ hai câu `UPDATE` như một lần chuyển tiền viết ngây thơ: S1 chuyển 100 từ 1 sang 2, S2 chuyển 100 từ 2 sang 1, cùng lúc.

```text
== Kịch bản 4: chuyển tiền bằng UPDATE, ngược chiều nhau (S1: 1→2, S2: 2→1)
19:25:30.312 [S1] >> BEGIN;
19:25:30.315 [S1] >> UPDATE accounts SET balance = balance - 100 WHERE id = 1;
19:25:30.385 [S1]    SET
19:25:30.385 [S2]    SET
19:25:30.388 [S1]    Timing is on.
19:25:30.388 [S2]    Timing is on.
19:25:30.390 [S1]    BEGIN
19:25:30.392 [S1]    Time: 0.051 ms
19:25:30.393 [S1]    UPDATE 1
19:25:30.395 [S1]    Time: 0.881 ms
19:25:31.316 [S2] >> BEGIN;
19:25:31.324 [S2] >> UPDATE accounts SET balance = balance - 100 WHERE id = 2;
19:25:31.324 [S2]    BEGIN
19:25:31.326 [S2]    Time: 0.134 ms
19:25:31.331 [S2]    UPDATE 1
19:25:31.333 [S2]    Time: 0.926 ms
19:25:32.320 [S1] >> UPDATE accounts SET balance = balance + 100 WHERE id = 2;
19:25:33.029 [S2] >> UPDATE accounts SET balance = balance + 100 WHERE id = 1;
19:25:33.329 [S2]    UPDATE 1
19:25:33.329 [S1]    Time: 1000.796 ms (00:01.001)
19:25:33.336 [S1]    ERROR:  deadlock detected
19:25:33.336 [S2]    Time: 291.436 ms
19:25:33.338 [S1]    DETAIL:  Process 497 waits for ShareLock on transaction 870; blocked by process 496.
19:25:33.342 [S1]    Process 496 waits for ShareLock on transaction 869; blocked by process 497.
19:25:33.348 [S1]    HINT:  See server log for query details.
19:25:33.354 [S1]    CONTEXT:  while updating tuple (0,2) in relation "accounts"
19:25:34.330 [S1] >> COMMIT;
19:25:34.336 [S1]    ROLLBACK
19:25:34.339 [S1]    Time: 0.115 ms
19:25:34.839 [S2] >> COMMIT;
19:25:34.858 [S2]    COMMIT
19:25:34.860 [S2]    Time: 10.700 ms
-- Sau khi cả hai phiên kết thúc:
 id | balance
----+---------
  1 | 1000100
  2 |  999900
(2 rows)

  tong
---------
 2000000
(1 row)
```

```text
2026-10-01 12:25:33.327 UTC [497] ERROR:  deadlock detected
2026-10-01 12:25:33.327 UTC [497] DETAIL:  Process 497 waits for ShareLock on transaction 870; blocked by process 496.
	Process 496 waits for ShareLock on transaction 869; blocked by process 497.
	Process 497: UPDATE accounts SET balance = balance + 100 WHERE id = 2;
	Process 496: UPDATE accounts SET balance = balance + 100 WHERE id = 1;
2026-10-01 12:25:33.327 UTC [497] HINT:  See server log for query details.
2026-10-01 12:25:33.327 UTC [497] CONTEXT:  while updating tuple (0,2) in relation "accounts"
2026-10-01 12:25:33.327 UTC [497] STATEMENT:  UPDATE accounts SET balance = balance + 100 WHERE id = 2;
```

## Dữ kiện quan sát được

| # | Dữ kiện | Ở đâu trong log |
|:-:|---|---|
| 1 | Khi S1 chờ dòng `id = 2` mà S2 đang giữ, `pg_stat_activity` báo `wait_event_type = Lock`, `wait_event = transactionid`, bị chặn bởi S2. Thông báo lỗi cũng ghi *"waits for ShareLock on **transaction** 852"*, chứ không ghi là chờ một dòng. | KB1, dòng OBS và DETAIL |
| 2 | S2 đang giữ khóa nhưng ở trạng thái `ClientRead` (đang chờ client gửi lệnh tiếp): một transaction rảnh vẫn giữ khóa dòng. | KB1, dòng OBS |
| 3 | S1 bắt đầu chờ lúc 21.565 và nhận `deadlock detected` sau 1001 ms, bằng `deadlock_timeout`. S2 tạo vòng chờ lúc 22.274, tức 0,3 giây trước khi lỗi xuất hiện. | KB1 |
| 4 | Nạn nhân là S1, phiên chờ trước nên hết `deadlock_timeout` trước và chạy bộ phát hiện deadlock. S2 lấy được dòng ngay sau đó (`Time: 291.998 ms`). | KB1 |
| 5 | Sau lỗi, `COMMIT` của S1 trả về `ROLLBACK`: toàn bộ transaction của S1 bị hủy, kể cả khóa trên `id = 1` mà nó đã giữ thành công. | KB1, dòng `>> COMMIT` của S1 |
| 6 | Cùng thứ tự khóa: S2 chờ `id = 1` mất 2008 ms (đến khi S1 commit) rồi chạy tiếp, không có lỗi. | KB2 |
| 7 | Kế hoạch thực thi có `LockRows` nằm **trên** `Sort`: các dòng được khóa theo thứ tự đã sắp xếp, không phụ thuộc thứ tự viết trong `IN (...)`. S2 chờ 1002 ms và không deadlock. | KB3, `EXPLAIN` |
| 8 | `UPDATE` cũng khóa dòng và deadlock y như `SELECT ... FOR UPDATE` (`while updating tuple`). Không có `FOR UPDATE` thì vẫn deadlock. | KB4 |
| 9 | Sau deadlock, chỉ giao dịch của S2 còn lại: số dư 1.000.100 và 999.900, tổng vẫn 2.000.000. Không có trạng thái nửa vời. | KB4, hai câu `SELECT` cuối |

## Kết luận

**1. Vì sao PostgreSQL báo chờ *transaction* chứ không phải chờ *dòng*?** PostgreSQL không giữ khóa dòng trong bảng khóa ở bộ nhớ chung, vì số dòng bị khóa có thể không giới hạn. Khóa dòng được ghi thẳng vào header của tuple (`xmax` cùng các bit cờ). Phiên đến sau đọc tuple, thấy nó đang bị transaction X khóa, rồi chờ bằng cách xin `ShareLock` trên **mã transaction** của X. Chính X giữ khóa `ExclusiveLock` trên mã của nó cho tới khi kết thúc. Vì vậy `pg_locks` không liệt kê từng dòng bị khóa. Muốn biết ai chặn ai thì dùng `pg_blocking_pids()` như phiên OBS (dữ kiện 1).

**2. Ai bị chọn làm nạn nhân, và ứng dụng phải làm gì?** Phiên nào chờ đủ `deadlock_timeout` trước thì chạy bộ kiểm tra. Nếu thấy vòng chờ, thường chính phiên đó bị hủy với `SQLSTATE 40P01` (dữ kiện 3, 4). Cả transaction bị rollback (dữ kiện 5, 9), nên ứng dụng phải **chạy lại từ đầu transaction**, không chỉ câu lệnh lỗi, với số lần thử có giới hạn và backoff. Trong Ledgerly, khóa có thứ tự làm 40P01 lẽ ra không bao giờ xảy ra. Nếu nó xuất hiện thì đó là bug, nên `DeadlockFreedomIT` (W04-04) khẳng định số lỗi 40P01 bằng 0 chứ không dựa vào retry để che.

**3. Vì sao một thứ tự khóa toàn cục loại bỏ được deadlock?** Deadlock cần một vòng trong đồ thị chờ. Nếu mọi transaction đều xin khóa theo `id` tăng dần, thì transaction đang giữ khóa lớn nhất là `k` chỉ có thể chờ một khóa lớn hơn `k`. Đi theo các mũi tên chờ, `id` tăng ngặt, nên không bao giờ quay về điểm xuất phát. Kịch bản 2 và 3 cho thấy điều đó: có chờ, nhưng không có vòng.

**4. `deadlock_timeout` lớn hay nhỏ thì sao, và khác `lock_timeout` thế nào?** Bộ kiểm tra deadlock tốn kém nên chỉ chạy khi một phiên đã chờ đủ `deadlock_timeout`. Đặt nhỏ thì phát hiện nhanh hơn nhưng chạy kiểm tra cả với những lần chờ bình thường. Đặt lớn thì transaction bị kẹt giữ khóa lâu hơn, làm tăng độ trễ của các phiên khác. `lock_timeout` là chuyện khác: hủy **bất kỳ** câu lệnh nào chờ khóa quá lâu, có deadlock hay không (`55P03`). Đó là cái chốt độ trễ mà W04-02 đặt 2 giây để trả 503 thay vì treo. Vì 2 giây lớn hơn 1 giây, deadlock thật (nếu có bug) vẫn được báo đúng là 40P01.

**5. Kịch bản 3 đảm bảo được gì và không đảm bảo được gì?** Một câu `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE` khóa các dòng theo thứ tự đã sắp xếp (dữ kiện 7), nên hai câu như vậy không bao giờ deadlock với nhau, dù danh sách `IN` viết theo thứ tự nào. Tài liệu PostgreSQL có cảnh báo: ở READ COMMITTED, `ORDER BY` cùng mệnh đề khóa có thể trả về dòng sai thứ tự nếu cột sắp xếp bị sửa trong lúc chờ. Ở đây ta sắp theo khóa chính, không bao giờ đổi, nên thứ tự khóa vẫn tăng dần. Nó **không** đảm bảo gì cho các khóa lấy ở câu lệnh khác. Kịch bản 4 cho thấy chỉ cần hai câu `UPDATE` theo thứ tự nghiệp vụ là đủ deadlock. Vì vậy quy tắc cho W04-01 là: **khóa mọi account của một lần posting ngay đầu transaction, trong một câu có `ORDER BY id`, trước mọi câu `UPDATE`**.

## Tự chạy tay (tùy chọn)

Muốn cảm nhận thời điểm bị chặn thì mở hai terminal và gõ từng lệnh của kịch bản 1:

```bash
# chuẩn bị
docker compose exec -T postgres psql -U ledgerly -d ledger -c 'CREATE DATABASE spike;'
docker compose exec -T postgres psql -U ledgerly -d spike -c "CREATE TABLE accounts (id int PRIMARY KEY, balance bigint NOT NULL); INSERT INTO accounts VALUES (1, 1000000), (2, 1000000);"

# terminal 1 và terminal 2
docker compose exec postgres psql -U ledgerly -d spike

# dọn dẹp
docker compose exec -T postgres psql -U ledgerly -d ledger -c 'DROP DATABASE spike;'
```
