# Cách tôi dùng AI

> Quy tắc ở [05-quy-uoc-lam-viec §7](05-quy-uoc-lam-viec.md#7-chính-sách-dùng-ai): AI là đồng nghiệp để hỏi và review, không sinh code mà mình không hiểu, và **không viết ADR thay mình**.

Mỗi mục ghi: hỏi AI gì, AI gợi ý gì, mình quyết định gì (chấp nhận hay bác bỏ, vì sao), và kiểm chứng bằng gì. Mục mới thêm lên **đầu** file.

```markdown
### YYYY-MM-DD: <chủ đề>
- Hỏi AI: ...
- AI gợi ý: ...
- Quyết định: chấp nhận / bác bỏ / sửa lại, vì ...
- Kiểm chứng: test, log hoặc PR
```

---

### 2026-10-08: Tuần 7, observability, baseline k6 và ADR-0007 (W07-01 … W07-07)
- Hỏi AI: "thực hiện task tiếp theo và kiểm tra cho kĩ mọi thứ đảm bảo phải đúng rồi push lên main".
- AI gợi ý:
  - Starter OpenTelemetry của Boot cho ba ứng dụng, mặc định không xuất gì, profile `observability` mới gửi đi. `traceparent` đi qua cột `headers` của outbox để một lần chuyển tiền là một trace từ HTTP tới consumer (PR #106).
  - Counter `ledgerly.transfers{outcome}` (PR #107). Container `grafana/otel-lgtm` và dashboard 11 panel trong repo (PR #108).
  - Kịch bản k6 vòng mở và script chạy ba lượt có kiểm tra bất biến sau mỗi lượt (PR #109). `docs/benchmarks.md`, ADR-0007 và tài liệu (PR #110).
- Quyết định: AI tự quyết và ghi vào ADR-0007, mình **chưa duyệt** điểm nào:
  - ADR-0007 được AI đặt trạng thái Accepted. So sánh với Java agent và với Prometheus, Jaeger rời là lập luận, chưa đo.
  - Lấy mẫu trace 10%. Span SQL không có giá trị tham số. Log chưa gửi lên Loki.
  - Thêm một thư viện ngoài BOM của Boot: `datasource-micrometer` 2.0.1.
  - Baseline đo với `ledger-app` ghim vào 2 nhân. SLO trong script giữ nguyên theo kế hoạch (p95 dưới 150 ms, p99 dưới 200 ms).
  - Lần đo hỏng được giữ trong repo (`perf/results/2026-10-08-attempt-1/`).
- AI làm sai và tự sửa trong tuần này, ghi lại để mình biết chỗ nào cần soi kỹ:
  - **Bài đo đầu tự làm nhiễu chính nó:** script chụp dashboard bằng Chrome giữa lượt đo, và ghi 400 MB output thô vào `/tmp` là RAM. Lượt 2 ra p99 746 ms. AI nhận ra khi đọc kết quả, sửa bài đo, đo lại.
  - **Ghi đè một file kết quả** của lần đo đầu, vì lệnh đổi tên thư mục lỗi mà không được kiểm tra.
  - **Counter đếm trước khi commit.** AI tìm ra khi đọc lại diff trong lúc chờ benchmark. Sửa, thêm test, rồi đo lại baseline trên code đã sửa.
  - **Script chạy benchmark lỗi ngay lần chạy thật đầu tiên** (đường dẫn tương đối cho volume của Docker), vì trước đó nó mới được kiểm tra cú pháp.
- Kiểm chứng: PR #106 đến #110. 237 test, hai lượt build sạch đều xanh, bảy đột biến đều bị test bắt. Baseline 300 request mỗi giây: p99 17,14 ms (trung vị ba lượt). Cả ngày 18 lượt đo, 2,27 triệu request, không request nào lỗi, hai script bất biến trả 0 dòng sau mọi lượt. **Còn mở:** những đợt server đứng 2 đến 12 giây chưa tìm ra nguyên nhân (issue #105), và chi phí của observation chưa có con số. Mình chưa đọc ADR-0007 và chưa tự chạy lại bài đo.

### 2026-10-07: Nghiên cứu và lập kế hoạch hướng AI Engineer
- Hỏi AI: dự án cần điều chỉnh gì để hợp cả vị trí AI Engineer, rồi "hãy nghiên cứu cho kĩ và đưa hướng đó vào dự án".
- AI gợi ý: sau khi tra tin tuyển dụng, spec MCP `2026-07-28`, hướng dẫn eval của Anthropic và OWASP Top 10 for Agentic Applications, đề xuất **một** tính năng: trợ lý ví gọi tool trên API thật. Ba tuần A1–A3 chen sau tuần 8: nền cho agent bằng Java (token theo scope, lệnh chờ xác nhận), service Python (MCP, agent, eval), rồi RAG, an toàn và so sánh model. Báo cáo có nguồn ở [research/huong-ai-engineer.md](research/huong-ai-engineer.md).
- Quyết định: **chưa có quyết định nào của mình.** AI đã viết kế hoạch vào lộ trình, charter, kiến trúc và chiến lược test, tất cả ở mức "dự kiến". Ba điểm mình phải chốt: ngân sách gọi model (ước 60–100 USD, chưa đo), thứ tự (sau tuần 8 hay sau tuần 11), và việc thêm xác thực tối thiểu (đang là non-goal). AI cũng đổi ý so với lời khuyên trước đó của chính nó: lúc đầu bảo không làm RAG, sau khi xem tin tuyển thì đưa RAG vào tuần A3 với một kho tri thức về chính sách.
- Kiểm chứng: chưa chạy thử thứ gì. Số liệu tuyển dụng là 20 tin trên ITviec trong một ngày và các bài tổng hợp của bên thứ ba. Giới hạn ghi ở cuối báo cáo.

### 2026-10-07: Tuần 6, transactional outbox, relay, consumer và ADR-0006 (W06-01 … W06-09)
- Hỏi AI: "hãy thực hiện kế hoạch tiếp theo".
- AI gợi ý:
  - Hợp đồng sự kiện là record Java thuần, kèm một sự kiện mẫu làm test fixture dùng chung cho producer và consumer (PR #98).
  - Bảng outbox và `OutboxWriter` với `Propagation.MANDATORY`. `TransferService.transfer` thành một transaction chứa bút toán và sự kiện (PR #99).
  - Relay: một vòng là một transaction với `FOR UPDATE SKIP LOCKED`, gửi lần lượt và chờ ack. Scheduler có backoff. Hai gauge đo độ trễ (PR #100).
  - Consumer khử trùng bằng `processed_events` trong cùng transaction với thông báo (PR #101). ADR-0006 và tài liệu (PR #102).
- Quyết định: AI tự quyết và ghi vào ADR-0006, mình **chưa duyệt** điểm nào:
  - Khác bản thiết kế: thêm cột `topic`, `created_at` dùng `clock_timestamp()`, nạp tiền nội bộ không phát sự kiện (I6 chỉ phát biểu cho chuyển tiền), thông báo chỉ cho ví nhận.
  - ADR-0006 được AI đặt `Accepted`. So sánh với Debezium, Kafka transaction và cách gửi cả lô là lập luận, chưa đo.
  - `RelayCrashDuplicateIT` chỉ khẳng định một trong ba điều kế hoạch nêu. Hai điều còn lại cần consumer, không chạy chung JVM test được, nên nằm ở `ConsumerDedupIT` và lần chạy tay.
  - **AI đã sai ba chỗ và sửa theo test hoặc số đo:** (1) dùng `@DirtiesContext`, làm Spring Boot dừng container dùng chung và 19 test đỏ; (2) đặt `created_at DEFAULT now()`, làm sai thứ tự sự kiện của cùng một aggregate, chỉ nhận ra khi viết đáp án phỏng vấn; (3) để `linger.ms` mặc định, làm relay chậm hơn 10 lần.
- Kiểm chứng: xem [kiểm chứng cuối của nhật ký tuần 6](journal/2026-W46.md#kiểm-chứng-cuối). Chạy tay hai ứng dụng thật với compose: dừng Kafka thì 30 lần chuyển vẫn 201 và outbox xả hết sau khi bật lại. `kill -9` relay giữa lúc xả 3.000 sự kiện: 3.050 giao dịch, 3.050 thông báo, 22 bản trùng được đếm, hai script bất biến trả 0 dòng. Lần chạy tay này chỉ có **một** lượt.

### 2026-10-07: Kiểm tra lại tuần 5
- Hỏi AI: "kiểm tra lại toàn bộ một lần nữa, đảm bảo các kế hoạch, các test đều hoàn chỉnh và đúng".
- AI gợi ý: build lại từ đầu không dùng cache, đối chiếu từng dòng của file tuần với code và test, kiểm mọi liên kết trong tài liệu, và sửa tạm code chính theo 18 cách để xem test nào đỏ.
- Quyết định: chấp nhận kết quả, gồm cả chỗ AI tự sai:
  - **AI đã viết sai vai trò của `lease_token`** trong ADR-0005, nhật ký và đáp án câu 3: nói rằng bỏ token thì "một key, hai giao dịch". Đo lại thì chỉ cần điều kiện `status` là vẫn một giao dịch, token chỉ quyết định bên nào thắng. Đã sửa cả ba chỗ và `01-kien-truc.md`.
  - **AI đã viết "bỏ token thì chỉ hai test đỏ, mọi test khác vẫn xanh"** khi mới chạy 13 trong số các test. Chạy đủ thì 4 test đỏ.
  - Thêm hai test cho chỗ còn hở: key biến mất giữa hai câu lệnh của Tx1, và vòng đời key sau khi hết hạn (vẫn replay tới khi bị xóa, xóa rồi thì dùng lại được).
- Kiểm chứng: `./gradlew build --rerun-tasks --no-build-cache` 176/176. Bảng 18 cách phá code và bảng ba phiên bản của câu hoàn tất ở [nhật ký tuần 5](journal/2026-W45.md#thí-nghiệm-phá-code-để-thử-test). 111 liên kết tương đối trong 11 file tài liệu đã đổi đều trỏ đúng.

### 2026-10-07: Tuần 5, idempotency hai pha và ADR-0005 (W05-01 … W05-09)
- Hỏi AI: "làm tiếp tuần 5 đi".
- AI gợi ý:
  - Bảng `idempotency_keys` và repository trước (PR #91): mỗi lần đổi trạng thái là một câu lệnh, điều kiện nằm trong `WHERE`.
  - `IdempotencyApi.execute` hai pha với `lease_token` làm fencing token, `RequestHasher` hash trên DTO đã parse (PR #92).
  - Nối vào `POST /v1/transfers` và `POST /v1/admin/deposits`: response render trong action để lưu cùng transaction với bút toán (PR #93).
  - Job dọn theo lô (PR #94), test 50 client và test crash bằng `FaultInjector` (PR #95), ADR-0005 và tài liệu (PR #96).
- Quyết định: AI tự quyết và ghi vào ADR-0005, mình **chưa duyệt** điểm nào:
  - Khác bản thiết kế: cột `JSON` thay cho `JSONB`, `locked_until <= now()` thay cho `<`, lưu **mọi** lời từ chối nghiệp vụ chứ không chỉ thiếu tiền, thời gian lấy từ `now()` của database thay vì tiêm `Clock`.
  - ADR-0005 được AI đặt `Accepted`. Phần so sánh với Redis và với thiết kế một transaction là lập luận, chưa đo.
  - AI tự chỉ ra một điểm yếu của kế hoạch: lý do "hai pha để gọi mạng" chưa được dùng tới, vì action chạy trong Tx2. Mình cần đọc phần Hệ quả của ADR-0005 và quyết định có giữ hai pha không.
  - `FaultInjector` là điểm móc chỉ để test nhưng nằm trong code chính.
- Kiểm chứng: `./gradlew build` 174/174 xanh (52 test mới). Test của #91–#93 viết trước và đã thấy đỏ (lỗi compile, rồi 7/11 test HTTP đỏ khi endpoint chưa nối). Test của #95 viết sau code, nên kiểm bằng cách sửa tạm: bỏ điều kiện `lease_token` thì hai test zombie đỏ. Chạy `bootTestRun` và gọi bằng `curl`: gửi ba lần cùng key, số dư chỉ đổi một lần (output ở [README](../README.md#retry-an-toàn-như-thế-nào)). Trước tuần 5, cùng thao tác đó chuyển tiền hai lần.

### 2026-10-07: VS Code tự build khi mở dự án
- Hỏi AI: vì sao bấm vào VS Code của dự án thì có thứ gì đó tự build, rồi nhờ xử lý.
- AI gợi ý: không có `tasks.json` nào chạy khi mở thư mục. Thứ tự build là ba extension: Language Support for Java (nạp dự án Gradle và biên dịch toàn bộ), Gradle for Java (bật daemon để đọc danh sách task), Spring Boot Tools. Thêm `"java.autobuild.enabled": false` và `"gradle.autoDetect": "off"` vào `.vscode/settings.json` (file ngoài git).
- Quyết định: chấp nhận cả hai. Đổi lại bảng Problems chỉ còn lỗi của file đang mở, và thanh Gradle không còn danh sách task.
- Kiểm chứng: **chưa** kiểm trong VS Code, mới kiểm file JSON hợp lệ.

### 2026-10-04: Tuần 4, khóa có thứ tự, test đồng thời, jqwik và ADR-0004 (W04-01 … W04-08)
- Hỏi AI: thực hiện tuần 4.
- AI gợi ý:
  - Làm `InvariantChecker` trước (PR #85): đọc chính `scripts/invariants.sql`, nên test và script chạy tay dùng một bộ truy vấn.
  - Viết ba test đồng thời trước khi có khóa và để chúng đỏ: 500/500 lần rút từ ví có 100 đồng đều thành công, deadlock khi chuyển ngược chiều. Sau đó mới thêm `lockAll` với `ORDER BY id FOR NO KEY UPDATE` (PR #86).
  - `lock_timeout` 2 giây cho từng transaction, đổi `55P03` thành `CannotAcquireLockException` ở repository, trả 503 kèm `Retry-After` (PR #86).
  - Property test bằng jqwik, nối Spring bằng `TestContextManager` (PR #87). Cổng coverage 80% cho domain (PR #88).
  - ADR-0004 với thí nghiệm bốn cách khóa, mỗi cách 3 lượt (PR #89).
- Quyết định: AI tự quyết và ghi vào ADR-0004: dùng `FOR NO KEY UPDATE` thay cho `FOR UPDATE`, nạn nhân deadlock cũng trả 503, `InvariantChecker` kiểm tra toàn bộ database (kéo theo việc sửa fixture của `SchemaConstraintsIT`). AI đã đoán sai một điểm và sửa theo số đo: tưởng một câu khóa không có `ORDER BY` thì không deadlock. Phần so sánh với SERIALIZABLE và khóa lạc quan trong ADR là lập luận, chưa đo. Mình cần đọc lại ADR-0004 trước khi coi nó là quyết định của mình.
- Kiểm chứng: PR #85–#89. `./gradlew check` 122/122 xanh, `ConcurrentTransferIT` xanh 10 lần liên tiếp, `LedgerModelProperties` 1.000 lượt thử. Mỗi test đều đã thấy đỏ trước: không khóa, không timeout, `InvariantChecker` rỗng, lỗi cài thử cho jqwik, coverage 0,74. `scripts/invariants.sql` chạy trên database compose trả 0 dòng.

### 2026-10-04: Tuần 3, API ví, Problem Details và ArchUnit (W03-05 … W03-09)
- Hỏi AI: thực hiện cho xong tuần 3.
- AI gợi ý:
  - `ArchitectureTest` làm trước (PR #82) với 7 quy tắc: không vòng, ma trận phụ thuộc, `internal` riêng tư, domain không dính framework, `@Transactional` chỉ ở tầng application, không field `double`/`float`. Ma trận viết thành một `Map` và một `ArchCondition` tổng quát thay vì lặp quy tắc cho từng module.
  - Module `wallet` (PR #83) chia `internal.application` (`WalletService`, `TransferService`, kết quả sealed) và `internal.web` (controller, DTO). Ví là account `USER_WALLET`. Account `SYSTEM` không được làm nguồn chuyển tiền.
  - `shared.problem`: `ProblemType` là bảng mã lỗi, `ProblemDetailsAdvice` kế thừa `ResponseEntityExceptionHandler`. Lỗi 400 của Spring MVC đổi thành `validation-error` kèm `errors`. Exception không lường trước thành 500 không lộ nguyên nhân.
  - `AbstractIntegrationTest` chạy server thật ở cổng ngẫu nhiên, test gọi qua `RestTestClient`.
- Quyết định: AI tự quyết các điểm khác thiết kế và đã sửa `01-kien-truc.md` §8 cho khớp: thêm bốn mã lỗi, bỏ trường `note` (schema chưa có cột), viết thẳng `/v1` thay vì dùng API versioning của Spring Framework 7, để `traceId` tới tuần 7, chỉ cho mở ví VND. Mình cần xem lại các điểm này, nhất là `note`.
- Kiểm chứng: PR #82, #83. Test viết trước: 43 test API đỏ vì `404` rồi mới xanh, 7 quy tắc ArchUnit đỏ với vi phạm cài thử. `./gradlew check` 112/112 xanh. Chạy `bootTestRun` và gọi mọi endpoint bằng `curl`, output thật ở [nhật ký tuần 3](journal/2026-W43.md#gọi-thử-bằng-curl).

### 2026-10-04: VS Code build liên tục và đầy RAM
- Hỏi AI: vì sao mở VS Code thì tự build và máy đầy RAM.
- AI gợi ý: đọc log của Gradle daemon và của Java language server. Extension Java build `build-logic` bằng Gradle 8.9 (thư mục không có wrapper), lỗi vì plugin Spring Boot cần Gradle 8.14 trở lên, và mỗi lần lỗi lại kích hoạt lần build kế: 1.553 lần `BUILD FAILED` trong ngày 03/10. Sửa bằng `java.import.gradle.version` trong `.vscode/settings.json`.
- Quyết định: chấp nhận. Thêm ba cấu hình cấp máy: heap language server 1 GB, heap Gradle daemon 1 GB (`~/.gradle/gradle.properties`), VS Code dùng cùng JDK với terminal để chung một daemon.
- Kiểm chứng: build lại toàn bộ với heap 1 GB chạy được (33 giây). Phần VS Code **chưa** kiểm chứng: cần mở lại VS Code và xem `~/.gradle/daemon/8.9/` còn sinh log mới không.

### 2026-10-04: GitHub Projects board (W01-06)
- Hỏi AI: xem tiến độ dự án. Sau đó mình tự cấp scope `project` cho `gh` (xác nhận mã thiết bị trên trình duyệt) để AI làm nốt W01-06.
- AI gợi ý: tạo board [Ledgerly](https://github.com/users/HoangThanhMan/projects/3) liên kết với repo, đổi field `Status` thành năm cột `Backlog`, `Todo`, `In progress`, `Review`, `Done` theo [lộ trình §7](03-lo-trinh.md#7-theo-dõi-tiến-độ-trên-github). Đưa cả 67 issue vào: issue đã đóng vào `Done`, việc còn lại của tuần 3 và #5 vào `Todo`, tuần 4–8 vào `Backlog`. PR dependabot #77 vào `Review`. Thêm một view dạng board bên cạnh view dạng bảng.
- Quyết định: board để **private** như repo. Các workflow tự chuyển cột (`Item closed`, `Pull request merged`) đang tắt và chỉ bật được trên giao diện, nên việc đó còn lại cho mình.
- Kiểm chứng: `gh project item-list 3` cho 68 mục: 40 `Backlog`, 6 `Todo`, 2 `Review` (#6 và PR #77), 20 `Done`.

### 2026-10-03: Tuần 3, `Money`, `PostingRules`, repository và `LedgerService` (W03-01 … W03-04)
- Hỏi AI: làm phần domain và persistence của tuần 3, rồi commit và merge vào `main`.
- AI gợi ý:
  - `Money` giữ `long` đơn vị nhỏ nhất (ADR-0003), tràn số ném `ArithmeticException`, khác currency ném `IllegalArgumentException`. `parsePositive` chỉ nhận chữ số ASCII vì `Long.parseLong` nhận cả chữ số Unicode khác.
  - `PostingRules` là Java thuần. Posting sai dạng (ít hơn hai, số 0, trùng account, tổng khác 0) là lỗi lập trình nên ném exception. Account không tồn tại, sai currency, thiếu tiền là kết quả nghiệp vụ, trả về dạng sealed `PostingResult` để caller phải `switch` đủ nhánh.
  - Repository dùng `JdbcClient` (ADR-0002). `findForPosting` đọc account theo thứ tự `id`, nên sau này thêm khóa chỉ cần `FOR UPDATE` mà không deadlock. Lịch sử dùng keyset trên index `(account_id, id)`.
  - `LedgerApi` là cổng duy nhất của module, `LedgerService` package-private và mỗi method có ranh giới `@Transactional` riêng.
- Quyết định: chấp nhận. Chưa có khóa dòng và idempotency, đúng phạm vi tuần 3.
- Kiểm chứng: `./gradlew build` xanh. `MoneyTest` 24, `PostingRulesTest` 12, `LedgerServiceIT` 7 (posting cập nhật số dư và entry, bị từ chối thì không đổi gì, keyset không trùng không sót).

### 2026-10-03: Chuyển comment trong code sang tiếng Anh
- Hỏi AI: kiểm tra toàn dự án, mọi comment phải viết bằng tiếng Anh, chỉ mô tả code, không tham chiếu kế hoạch tuần (W0x-yy, "tuần N").
- AI gợi ý: dịch comment trong Java (Javadoc, `package-info`), Gradle Kotlin DSL, SQL migration, YAML, TOML, Python và shell. Bỏ mọi mã task và "Tuần triển khai". Thay mã bất biến (I1, I2, P2) bằng mô tả. Giữ tham chiếu tới ADR và tài liệu kiến trúc vì đó là lý do thiết kế, không phải kế hoạch. Dịch luôn `description` của các module Gradle và output của `coverage-summary.py` (dòng "Tuần 2 chỉ báo cáo…" bị bỏ). Không đổi output của `scripts/spikes/locking.sh` (log trong `docs/journal/spike-locking.md` trích nguyên văn) và issue form (người dùng đọc, cùng ngôn ngữ với tài liệu).
- Quyết định: chấp nhận. Sửa comment trong V1, V2 làm đổi checksum Flyway: chấp nhận vì chưa có môi trường nào ngoài máy dev. Database compose cũ cần `docker compose down -v`.
- Kiểm chứng: `./gradlew build` xanh (14 integration test). Gặp một lỗi lạ của Kotlin DSL: khi `ledgerly.spring-boot-app.gradle.kts` dài đúng 1071 byte thì `build-logic:compileKotlin` không sinh accessor. Lỗi tái hiện ổn định, đổi độ dài 1 ký tự là hết, nên đã viết lại câu comment.

### 2026-10-01: Tuần 2, chất lượng build và schema lõi (W02-01 … W02-09)
- Hỏi AI: "Thực hiện luôn tuần 2".
- AI gợi ý: 4 PR code xếp chồng (#72–#75) và 1 PR tài liệu (#76). Phiên bản plugin lấy bản mới nhất trên Maven Central và Gradle Plugin Portal ngày 01/10/2026, rồi kiểm chứng bằng build thật trên JDK 25.
- Quyết định:
  - **Bác bỏ** `failOnNoDiscoveredTests = false` để `src/test` chứa được lớp hỗ trợ. Thay vào đó chuyển lớp hỗ trợ sang `src/integrationTest` và trỏ `bootTestRun` sang suite đó. Giữ lưới an toàn của Gradle.
  - **Bác bỏ** image `apache/kafka-native` trong Testcontainers sau khi đo: segfault 2/15 lần khởi động. Dùng `apache/kafka` (JVM), 15/15.
  - **Thêm ngoài kế hoạch:** chặn `TRUNCATE`, cho `ledger_transactions` chỉ được thêm, ràng buộc "giao dịch phải có entry" và kiểm tra mã ISO 4217. Mỗi ràng buộc có test riêng.
  - Merge PR bị chặn quyền, nên PR được xếp chồng để tác giả merge một lượt.
- Kiểm chứng: lỗi null, Error Prone và format cố ý thêm vào đều làm build đỏ. `SchemaConstraintsIT` đi qua hai bước RED (bảng chưa có, rồi bảng chưa có ràng buộc) trước khi GREEN 13/13. Đếm container: 2 context, 1 PostgreSQL. 5 lượt suite có Kafka xanh liên tiếp.

### 2026-10-01: Giao toàn bộ tuần 1 cho AI
- Hỏi AI: "Mọi việc để bạn làm, hãy thực hiện cho xong tuần 1". Lệnh này thay cho lựa chọn trước đó (AI chỉ chuẩn bị, mình viết lập luận, mình merge).
- AI gợi ý: viết trọn ADR-0003 (Accepted), 6 ghi chú đọc, nhật ký W41, kết luận spike, đáp án tham khảo cho 5 câu hỏi M1, và sửa §7.3 cho khớp thực tế.
- Quyết định: chấp nhận. §7.3 đổi từ "không dùng AI viết ADR thay mình" thành "AI được soạn nháp ADR khi được yêu cầu, tác giả phải review, hiểu và chịu trách nhiệm từng dòng". Ghi chú hai cuốn sách (SDI Vol. 2, DDIA) viết từ kiến thức chung, chưa đối chiếu sách.
- Kiểm chứng: PR #68–#71. Merge PR, bảo vệ nhánh `main`, chuyển repo public và tạo Projects board vẫn phải do mình làm, vì quyền của AI bị chặn.

### 2026-10-01: Mẫu issue, khung ghi chú đọc và nhật ký (W01-07, W01-01, W01-09)
- Hỏi AI: làm W01-07, và chuẩn bị cho W01-01, W01-09.
- AI gợi ý: issue form `task` và `bug` (bug có ô chọn bất biến I1–I7). Thư mục `docs/journal/` có khung cho 6 tài liệu đọc và khung nhật ký tuần, kèm câu hỏi gợi ý. AI không viết nội dung ghi chú hay cảm nhận thay mình.
- Quyết định: chấp nhận. Sau đó (xem mục trên) AI viết luôn nội dung ghi chú và nhật ký.
- Kiểm chứng: PR #71. Tạo thử một issue bằng mẫu `task` sau khi merge.

### 2026-10-01: Spike khóa SQL (W01-08)
- Hỏi AI: làm spike khóa dòng và deadlock của tuần 1.
- AI gợi ý: script `scripts/spikes/locking.sh` chạy hai phiên `psql` theo kịch bản thời gian cố định, có ba kịch bản (khóa ngược thứ tự, cùng thứ tự, một câu `ORDER BY id FOR UPDATE`). AI chạy script và ghi log thật vào `docs/journal/spike-locking.md`.
- Quyết định: giữ script để tái hiện được. AI thêm kịch bản 4 (`UPDATE` ngược chiều cũng deadlock) và viết kết luận. Từ đó rút ra quy tắc cho W04-01.
- Kiểm chứng: PR #70, log deadlock phía server, 5 lần chạy cho cùng kết quả.

### 2026-10-01: ADR-0003, biểu diễn tiền (W01-03)
- Hỏi AI: làm W01-03.
- AI gợi ý: vì quy tắc "không dùng AI viết ADR thay mình", AI hỏi lại cách làm. Mình chọn để AI chạy thí nghiệm và dựng khung, còn lập luận và quyết định do mình viết.
- Quyết định: ban đầu để mình viết lập luận, sau đó giao cho AI viết trọn. Chọn `long` theo đơn vị nhỏ nhất + ISO 4217, một cột `amount` có dấu, API trả chuỗi số nguyên. Bác bỏ `BigDecimal` (cấp phát object, bẫy `equals`) và `amount` + `direction` (phải tra loại account mới biết tăng hay giảm).
- Kiểm chứng: PR #69. Bằng chứng là output thật của jshell 25, Node 20 và PostgreSQL 18.6.

### 2026-10-01: Review tài liệu kiến trúc (W01-02)
- Hỏi AI: review `01-kien-truc.md`, tập trung vào mô hình dữ liệu và luồng 6.1, 6.2.
- AI gợi ý: 8 điểm. Hai điểm quan trọng: (1) luồng idempotency hai pha cho phép một key sinh ra hai giao dịch khi Tx2 chạy lâu hơn `locked_until`, sửa bằng `lease_token` (fencing token); (2) `reconciliation` cần gọi `topup` nhưng ma trận phụ thuộc cấm. Ngoài ra có nhận xét cho ADR-0001 và ADR-0002 nhưng AI không sửa ADR.
- Quyết định: chấp nhận cả 8 điểm. Sửa luôn hai chỗ trong ADR-0002 (MySQL **không có** deferred constraint, thêm nguồn số liệu Stack Overflow 2025).
- Kiểm chứng: PR #68. Ca *zombie* được thêm vào kế hoạch `IdempotencyCrashRecoveryIT` (tuần 5).

### 2026-10-01: Repo GitHub và backlog (W01-05, W01-06)
- Hỏi AI: tạo repo, push code, tạo label, milestone và issue cho tuần 2–8.
- AI gợi ý: label theo `type:` / `area:` / `week:` / `moscow:`, milestone M1–M5 theo [lộ trình](03-lo-trinh.md#3-mốc-và-điều-kiện-qua-mốc), mỗi task trong file tuần là một issue (67 issue cho tuần 1–8).
- Quyết định: repo để **private** cho tới khi mình tự chuyển sang public. Mình tự merge PR sau khi tự review. Bảo vệ nhánh `main` và tạo Projects board do mình tự làm (AI không có quyền).
- Kiểm chứng: issue #1–#67 khớp bảng công việc trong `docs/weeks/tuan-01.md` … `tuan-08.md`.
