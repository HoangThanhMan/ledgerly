# ADR-0005: Idempotency key trong PostgreSQL, thiết kế hai pha

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Tuần:** 5
- **Liên quan:** [01-kien-truc §6.1](../01-kien-truc.md#61-chuyển-tiền-idempotent-tuần-35), ADR-0002, ADR-0004, issue #36–#44, [ghi chú bài của Stripe](../journal/notes-stripe-idempotency.md)

> ADR này do AI soạn theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Số liệu trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-07 trên máy dev (7 GB RAM, PostgreSQL 18 trong Testcontainers). Phần so sánh với Redis và với thiết kế một transaction là **lập luận**, chưa đo. Chi phí của idempotency trên mỗi request cũng **chưa đo**.

## Bối cảnh

Client gửi `POST /v1/transfers` rồi mất kết nối hoặc hết thời gian chờ thì không biết server đã chuyển tiền hay chưa. Cách duy nhất nó làm được là gửi lại. Trước tuần 5, header `Idempotency-Key` chỉ được kiểm tra định dạng: gửi lại cùng key thì tiền chuyển lần hai (B5). Bất biến cần giữ là **I5**: một key sinh ra tối đa một giao dịch, và mọi lần gọi nhận cùng một kết quả.

Các lực tác động:

- **Kết quả đã lưu phải đi cùng tiền.** Không được có trạng thái "tiền đã chuyển nhưng key chưa có kết quả", vì lần retry kế sẽ chuyển lần nữa. Chiều ngược lại cũng sai.
- **Tiến trình có thể chết ở bất kỳ dòng nào**, kể cả giữa hai câu lệnh.
- **Request trùng đến đồng thời**, không chỉ lần lượt: client hết thời gian chờ sớm sẽ gửi lại khi request đầu còn đang chạy.
- **Pool nhỏ.** HikariCP mặc định 10 connection, một posting có thể chờ khóa tới 2 giây (ADR-0004). Request nào đứng chờ trong database là một connection bị giữ.
- **Tuần 9 có luồng qua ngân hàng.** Không được giữ transaction của database trong lúc chờ mạng.
- **Hạ tầng tối thiểu.** Dự án đã có PostgreSQL và Kafka, mỗi thành phần thêm vào là một thứ phải vận hành và phải giải thích.

## Các phương án đã cân nhắc

### Câu hỏi 1: Lưu key ở đâu

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Bảng `idempotency_keys` trong cùng PostgreSQL | Response được lưu **trong cùng transaction** với bút toán: hoặc cả hai có, hoặc không có gì. Không thêm hạ tầng. Tranh chấp do unique index và `UPDATE ... WHERE` phân xử | Mỗi request thêm câu lệnh ghi và một transaction. Bảng lớn dần, cần job dọn. Toàn bộ tải dồn lên database chính |
| B. Redis (`SET key NX` kèm TTL) | Nhanh. TTL tự dọn | Ghi vào hai hệ thống (dual write): giữa `COMMIT` của PostgreSQL và lệnh ghi Redis luôn có một khe. Chết trong khe đó thì tiền đã chuyển mà key chưa có kết quả, hoặc ngược lại. Thêm một hệ thống phải vận hành. Độ bền phụ thuộc cấu hình lưu trữ và failover |
| C. Cột `UNIQUE` trên `ledger_transactions` chứa key | Đơn giản nhất, không cần bảng mới | Không lưu được response để phát lại nguyên văn. Không lưu được lời từ chối, vì từ chối thì không có transaction. Không dùng được cho luồng chưa tạo bút toán ngay (nạp tiền qua ngân hàng) |

### Câu hỏi 2: Một transaction hay hai pha

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Một transaction: `INSERT` key, nghiệp vụ và response cùng commit | Ít trạng thái nhất: không có `IN_PROGRESS`, không cần lease. Chết giữa chừng thì rollback hết | Request trùng **đứng chờ** ở unique index tới khi request đầu commit, và giữ một connection suốt thời gian đó. Nếu action phải gọi mạng thì transaction mở suốt lúc chờ |
| Hai pha: Tx1 claim key, rồi Tx2 chạy nghiệp vụ và hoàn tất key | Request trùng được trả lời **ngay** (409) thay vì giữ connection để chờ. Giữa hai pha không có transaction nào mở. Key đang xử lý là một trạng thái nhìn thấy được | Thêm trạng thái `IN_PROGRESS`, kéo theo lease, giành lại key và fencing token. Client phải xử lý 409. Thêm một transaction cho mỗi request |

### Câu hỏi 3: Request giữ key bị chết hoặc quá chậm

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Key ở `IN_PROGRESS` mãi | Không cần gì thêm | Một lần chết làm key kẹt tới khi hết TTL: client không bao giờ nhận được kết quả |
| Lease có hạn (`locked_until`), câu hoàn tất **không có điều kiện** | Tự phục hồi sau khi tiến trình chết | Request đầu **chưa chết mà chỉ chậm** vẫn commit được sau khi key đã bị giành: một key, hai giao dịch (đo ở B4) |
| Lease, câu hoàn tất có điều kiện `status = 'IN_PROGRESS'` | Chỉ một bên commit được: bên hoàn tất sau thấy trạng thái đã đổi và rollback (đo ở B4) | Bên thắng có thể là request **đã mất lease**. Việc hoàn tất gắn với key chứ không gắn với lần claim. Bước thả key không phân biệt được lease của mình với lease của request đã giành lại |
| Lease kèm **fencing token** (`lease_token`), đổi mỗi lần claim hoặc giành lại, thêm vào điều kiện trên | Chỉ request đang giữ lease mới hoàn tất và thả được key | Thêm một cột và một điều kiện trong câu `UPDATE` |

### Câu hỏi 4: Lưu những kết quả nào

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Chỉ lưu thành công | Đơn giản | Lần retry của một request bị từ chối sẽ chạy lại nghiệp vụ và có thể **thành công**, tức hai lần gọi cùng key nhận hai kết quả khác nhau |
| Lưu thành công và mọi lời từ chối nghiệp vụ | Cùng key thì luôn cùng câu trả lời | Lời từ chối bị "đóng băng": ví thiếu tiền, nạp thêm, gửi lại cùng key vẫn nhận 422. Client phải dùng key mới cho lần thử mới |
| Lưu cả lỗi 5xx (cách của Stripe) | Thống nhất tuyệt đối | Lỗi kỹ thuật ở đây luôn rollback, tức chưa có hiệu ứng nào. Lưu nó là bắt client đổi key cho một request chưa hề được xử lý |

### Câu hỏi 5: So request với request gốc bằng gì

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Hash chuỗi body thô | Không cần parse | Đổi thứ tự trường hoặc khoảng trắng là thành "request khác" |
| Hash DTO đã parse, ghi lại theo thứ tự trường cố định | So theo **nghĩa** của request | Hash phụ thuộc cách serialize: đổi cấu hình JSON là đổi hash của các key đang lưu |

## Quyết định

Chúng tôi sẽ lưu key trong **bảng `idempotency_keys` của PostgreSQL** (1A) và xử lý theo **hai pha có fencing token** (hai pha ở câu hỏi 2, phương án cuối ở câu hỏi 3):

```sql
-- Tx1: claim. 1 dòng là giữ được key.
INSERT INTO idempotency_keys (idem_key, request_hash, status, lease_token, locked_until, expires_at)
VALUES (:key, :hash, 'IN_PROGRESS', :token, now() + 30s, now() + 24h)
ON CONFLICT (idem_key) DO NOTHING;

-- Tx1, khi key đã tồn tại, đang IN_PROGRESS và cùng hash: giành lại nếu lease đã hết.
UPDATE idempotency_keys SET lease_token = :token, locked_until = now() + 30s
WHERE idem_key = :key AND status = 'IN_PROGRESS' AND locked_until <= now();

-- Tx2: sau khi action chạy xong, cùng transaction với bút toán. 0 dòng thì rollback.
UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ..., response_body = ..., resource_id = ...
WHERE idem_key = :key AND lease_token = :token AND status = 'IN_PROGRESS';
```

Key đã tồn tại thì: khác hash trả `422 idempotency-key-reused`, `COMPLETED` trả lại response đã lưu kèm `Idempotent-Replayed: true`, còn lease thì trả `409 idempotency-in-progress` kèm `Retry-After: 1`.

Các quyết định đi kèm:

- **Lưu mọi kết quả mà action trả về như một giá trị** (phương án giữa ở câu hỏi 4): 201 và mọi lời từ chối nghiệp vụ. Action ném exception thì Tx2 rollback, không lưu gì, và key được thả (`locked_until = now()`) để client retry ngay.
- **Hash trên DTO đã parse** (câu hỏi 5): SHA-256 của `method + "\n" + path + "\n" + JSON có trường xếp theo bảng chữ cái`. `RequestHasher` dùng mapper riêng, không dùng mapper của API. Method và path nằm trong hash để một key không dùng lại được ở endpoint khác.
- **Response lưu ở cột `JSON`, không phải `JSONB`.** Response được render thành chuỗi ngay trong action, và replay trả lại đúng chuỗi đó.
- **Thời gian lấy từ `now()` của database**, nên các instance không cần đồng hồ khớp nhau.
- **TTL 24 giờ là tối thiểu.** Job dọn chạy 10 phút một lần, mỗi lô 1000 key trong một transaction riêng, dùng `FOR UPDATE SKIP LOCKED`.
- Lease 30 giây, `Retry-After` 1 giây, TTL 24 giờ đều cấu hình được (`ledgerly.idempotency.*`).

Redis bị loại vì khe dual write đúng là lỗi mà tính năng này phải chặn. Thiết kế một transaction bị loại vì request trùng sẽ giữ connection để chờ, trong khi pool chỉ có 10.

## Hệ quả

- **Tích cực:** tiền và response đã lưu là một transaction, nên không có trạng thái lệch giữa chúng (B2). Request trùng đến đồng thời được trả lời ngay và cuối cùng nhận đúng một kết quả (B3). Tiến trình chết sau khi claim thì lần retry sau khi lease hết sẽ làm nốt. Không thêm hạ tầng.
- **Tiêu cực / đánh đổi:**
  - Mỗi request mới thêm hai câu lệnh ghi (`INSERT`, `UPDATE`) và một transaction. Một replay tốn một `INSERT` không thành và một `SELECT`. Chi phí này chưa đo, tuần 7 mới có baseline.
  - Client phải hiểu 409 và gửi lại theo `Retry-After`. Trong test, 28 đến 43 trên 50 client đồng thời nhận 409 ít nhất một lần.
  - Lời từ chối bị đóng băng theo key (xem câu hỏi 4).
  - Thiết kế có nhiều trạng thái hơn hẳn phương án một transaction. I5 chỉ đúng nếu **mọi** đường hoàn tất key đều là một `UPDATE` có điều kiện, nằm trong cùng transaction với nghiệp vụ (B4).
- **Lý do "gọi mạng" yếu hơn kế hoạch đã viết.** Kế hoạch tuần 5 nói hai pha cần cho luồng gọi ngân hàng. Nhưng trong cài đặt này action chạy **bên trong Tx2**, và theo [§6.3](../01-kien-truc.md#63-nạp-tiền-qua-ngân-hàng-saga-có-trạng-thái-tuần-9) thì `POST /v1/topups` chỉ ghi lệnh `PENDING` rồi trả 202, còn cuộc gọi ngân hàng do saga làm sau đó. Tức là các action dự kiến đều chỉ đụng database. Lý do còn đứng vững là request trùng không giữ connection. Nếu tuần 9 cần một action gọi mạng trước khi trả lời, `IdempotencyApi` phải có thêm biến thể chạy action ngoài transaction.
- **Key là toàn cục.** Chưa có xác thực nên key không gắn với người gọi: hai client dùng cùng key và cùng body thì client sau nhận kết quả của client trước. Khi có xác thực, key phải được scope theo người gọi.
- **Lease phải dài hơn Tx2.** 30 giây so với `lock_timeout` 2 giây là dư nhiều. Lease quá ngắn không làm sai dữ liệu (fencing chặn) nhưng làm request hợp lệ bị rollback.
- **Cần theo dõi:** counter `ledgerly.idempotency.replays`, tỉ lệ 409, số dòng của bảng, và số key `IN_PROGRESS` đã quá `locked_until` (dấu hiệu tiến trình chết giữa chừng). Ba chỉ số sau chưa có metric.

## Bằng chứng

**B1. Test.** `./gradlew build --rerun-tasks --no-build-cache` ngày 2026-10-07: 176/176 xanh, trong đó 54 test mới của tuần 5.

| Test | Số ca | Khẳng định chính |
|---|:-:|---|
| `IdempotencyKeyRepositoryIT` | 16 | Từng câu lệnh: claim, giành lại chỉ khi lease hết, hoàn tất chỉ khi khớp token, key đã xong không bị giành, xóa theo lô |
| `IdempotencyApiIT` | 10 | Các nhánh của `execute`, action và completion là một transaction, lỗi thì rollback và thả key |
| `IdempotencyIT` | 11 | Hành vi qua HTTP: replay giống lần đầu từng byte, 422, 409, lời từ chối được lưu, lỗi 503 không được lưu |
| `IdempotencyConcurrencyIT` | 2 | 50 client, một key: 1 giao dịch, 50 câu trả lời giống nhau, 49 replay |
| `IdempotencyCrashRecoveryIT` | 3 | Chết sau khi claim, lỗi trước khi hoàn tất, và ca zombie |
| `IdempotencyCleanupIT` | 5 | Key hết hạn bị xóa, key còn hạn giữ nguyên, 2.500 key qua 3 lô, key đã xóa dùng lại được cho request khác |
| `RequestHasherTest`, `ProblemDetailsAdviceTest`, `IdempotencyTransactionsTest` | 4 + 2 + 1 | Hash không phụ thuộc thứ tự trường, 409 có `Retry-After`, key biến mất giữa hai câu lệnh của Tx1 |

**B2. Tiền và response là một transaction.** `IdempotencyCrashRecoveryIT.failureAfterTheMoneyMovedButBeforeTheKeyIsCompletedUndoesTheTransfer`: ném exception sau khi bút toán đã ghi và trước câu `UPDATE ... COMPLETED`. Kết quả: 0 giao dịch, số dư không đổi, key vẫn `IN_PROGRESS`, lần retry ngay sau đó nhận 201.

**B3. 50 request đồng thời.** 50 virtual threads cùng xuất phát từ một `CountDownLatch`, cùng key, cùng body, gặp 409 thì chờ theo `Retry-After` rồi gửi lại. Số của một lượt chạy ngày 2026-10-07:

| Ca | Giao dịch tạo ra | Câu trả lời cuối | Có `Idempotent-Replayed` | Thời gian |
|---|:-:|---|:-:|:-:|
| Chuyển 300 từ ví có 1.000 | 1 | 50 × 201, cùng body | 49 | 1,1 giây |
| Chuyển 101 từ ví có 100 | 0 | 50 × 422, cùng body | 49 | 2,0 giây |

Số client nhận 409 ít nhất một lần là 28 ở một ca và 43 ở ca kia. Log của test không ghi số nào thuộc ca nào.

**B4. Điều kiện của câu hoàn tất, đo trên ca zombie.** Sửa tạm phần `WHERE` của câu `UPDATE ... COMPLETED`, chạy kịch bản zombie (request A bị giữ lại trong Tx2 quá lease 1 giây, request B giành key, ví nguồn có 100 và chuyển 10), rồi hoàn nguyên:

| Điều kiện ngoài `idem_key` | A (zombie) nhận | B (đã giành key) nhận | Số giao dịch | Ví nguồn còn |
|---|:-:|:-:|:-:|:-:|
| `lease_token = :token AND status = 'IN_PROGRESS'` (quyết định) | 409 | 201 | 1 | 90 |
| Chỉ `status = 'IN_PROGRESS'` | 201 | 409 | 1 | 90 |
| Không có | 201 | 201 | **2** | 80 |

Điều đo được: thứ chặn giao dịch thứ hai là **điều kiện `status`** cùng với việc câu `UPDATE` nằm trong transaction của nghiệp vụ. Token quyết định **bên nào** thắng. Vì vậy lý do giữ token là lập luận, không phải số đo:

- Việc hoàn tất gắn với đúng một lần claim. Nếu chỉ so `status`, một request treo đủ lâu để key bị job dọn xóa rồi được claim lại cho một request **khác** vẫn hoàn tất được dòng mới đó, và request kia sẽ nhận response không phải của mình. Cần treo hơn 24 giờ nên gần như không xảy ra, nhưng token loại hẳn trường hợp này.
- Bước thả key sau lỗi cần token, để một request đã mất lease không thả lease của request đang giữ.
- Response được lưu luôn là của request đang giữ lease, nên dễ lần theo khi điều tra.

**B7. Thử phá code.** 18 cách sửa hỏng code (bỏ từng điều kiện trong SQL, bỏ `@Transactional` của Tx2, không thả key, không lưu lời từ chối, bỏ từng thành phần của hash, ...) đều làm ít nhất một test đỏ. Bảng đầy đủ ở [nhật ký tuần 5](../journal/2026-W45.md#thí-nghiệm-phá-code-để-thử-test).

**B5. Trước và sau, gọi bằng `curl` trên app thật.** Trước tuần 5 (commit `4e73b95`): gửi hai lần cùng key `…-tr1`, ví nguồn từ 350.000 xuống 200.000. Sau tuần 5 (`bootTestRun`): gửi ba lần cùng key, lần ba đổi thứ tự trường và thêm khoảng trắng, ví nguồn vẫn 350.000 và hai lần sau có `Idempotent-Replayed: true`. Output ở [README](../../README.md#how-retries-stay-safe).

**B6. Tài liệu.** Stripe, *Designing robust and predictable APIs with idempotency* và tài liệu API *Idempotent requests*, đã ghi chú ở [notes-stripe-idempotency](../journal/notes-stripe-idempotency.md). Khác Stripe ở hai điểm: không lưu lỗi 5xx, và key tối đa 64 ký tự.
