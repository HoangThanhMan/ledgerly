# ADR-0007: Observability bằng OpenTelemetry qua Boot starter và Grafana LGTM

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-08
- **Tuần:** 7
- **Liên quan:** [01-kien-truc §10](../01-kien-truc.md#10-quan-sát-hệ-thống-observability), ADR-0006, issue #54–#60, [benchmarks](../benchmarks.md)

> ADR này do AI soạn theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Số liệu trong phần Bằng chứng là kết quả chạy thật ngày 2026-10-08 trên máy dev. Phần so sánh với Java agent và với bộ Prometheus, Jaeger rời là **lập luận**, chưa đo.

## Bối cảnh

Tới hết tuần 6, hệ thống có ba tiến trình (`ledger-app`, `notification-consumer`, `mock-bank`), một relay chạy nền và Kafka ở giữa. Khi một lần chuyển tiền chậm hoặc một thông báo không tới, cách duy nhất để tìm nguyên nhân là đọc log của từng tiến trình. Tuần 7 cũng cần một con số hiệu năng đầu tiên, và con số đó vô dụng nếu không chỉ ra được nút cổ chai nằm ở đâu.

Các lực tác động:

- **Một request đi qua ba chỗ không nối với nhau:** HTTP, rồi relay chạy sau đó trên luồng khác, rồi consumer ở tiến trình khác.
- **Máy dev có 7 GB RAM.** Hạ tầng quan sát không được nặng hơn thứ nó quan sát.
- **Không thêm rủi ro tương thích.** Dự án chạy Spring Boot 4.1 và Jackson 3, hệ sinh thái còn mới.
- **Số liệu phải kiểm chứng được:** metric và trace sinh ra từ cùng một nguồn, có test.
- **Không để lộ dữ liệu:** tham số của câu SQL chứa số tiền và id ví.

## Các phương án đã cân nhắc

### Câu hỏi 1: Gắn đo đạc vào ứng dụng bằng gì

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. `spring-boot-starter-opentelemetry`: Micrometer Observation trong tiến trình, xuất bằng OTLP | Là một dependency của Boot, phiên bản do BOM quản lý. Metric nghiệp vụ đã viết bằng Micrometer từ tuần 4 dùng lại nguyên. Test được bằng một exporter trong bộ nhớ | Chỉ đo những thư viện có hỗ trợ Observation. JDBC cần thêm một thư viện ngoài |
| B. OpenTelemetry Java agent (`-javaagent`) | Không sửa code, phủ rất nhiều thư viện | Sửa bytecode lúc chạy, thêm thời gian khởi động. Là một file ngoài build, phiên bản phải tự khớp. Khó test trong `./gradlew build` |
| C. Micrometer với Prometheus scrape, cộng một tracer riêng | Quen thuộc, nhiều tài liệu | Hai đường xuất, hai cấu hình. Scrape cần ứng dụng mở cổng cho Prometheus, không hợp khi chạy bằng `java -jar` ngoài compose |

### Câu hỏi 2: Lưu và xem ở đâu

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| `grafana/otel-lgtm`: collector, Prometheus, Tempo, Loki, Grafana trong một container | Một service trong compose, một cổng nhận OTLP. Dashboard nạp từ file trong repo | Image nặng (3,67 GB). Chỉ dành cho dev và demo, không có lưu trữ bền |
| Prometheus, Jaeger, Grafana riêng | Từng thành phần quen thuộc, cấu hình sâu được | Ba service, ba cấu hình, phải tự nối datasource |

### Câu hỏi 3: Nối trace qua outbox

Relay phát sự kiện sau khi request đã trả lời, trên một luồng khác. Không có gì tự nối hai việc đó.

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Không nối | Không phải làm gì | Trace của request dừng ở `COMMIT`. Việc phát sự kiện và việc của consumer là các trace rời, không biết thuộc request nào |
| Lưu `traceparent` vào dòng outbox, relay khôi phục khi gửi | Một trace từ HTTP tới consumer. Dùng đúng cột `headers` đã có | Trace kéo dài bằng độ trễ của relay. Thêm một giá trị nhỏ vào mỗi dòng outbox |
| Trace mới cho relay, có *span link* về request | Đúng ngữ nghĩa "việc xảy ra sau" | Phải bấm qua link mới thấy, khó đọc hơn khi demo |

### Câu hỏi 4: Lấy mẫu bao nhiêu

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| 100% | Request nào cũng có trace | Ở 300 request mỗi giây là khoảng 5.400 span mỗi giây cho một container đã nặng |
| 10% (mặc định của Boot) | Chi phí thấp, vẫn đủ trace để xem | Request cần xem có thể không được lấy mẫu |

## Quyết định

Chúng tôi sẽ dùng **`spring-boot-starter-opentelemetry`** (1A) cho cả ba ứng dụng, xuất metric và trace bằng OTLP tới **`grafana/otel-lgtm`** trong profile `observability` của compose.

- **Mặc định không xuất gì.** Ứng dụng chạy bằng `./gradlew bootRun` hay trong test không cần collector. Profile `observability` của Spring bật xuất metric (10 giây một lần) và trace tới `localhost:4318`.
- **Trace đi qua outbox bằng `traceparent`** lưu trong cột `headers` (phương án giữa ở câu hỏi 3). `OutboxService` ghi ngữ cảnh trace hiện tại khi ghi sự kiện, `KafkaEventPublisher` khôi phục nó và mở span `outbox publish` quanh lần gửi. Observation của `KafkaTemplate` và của listener được bật, nên `traceparent` nằm trong header của record và consumer nối tiếp.
- **SQL có span** nhờ `datasource-micrometer` 2.0.1, chỉ cho câu lệnh (`jdbc.includes: query`), **không** kèm giá trị tham số.
- **Lấy mẫu 10%** theo mặc định, 100% trong profile `observability` để xem khi phát triển. Baseline chạy ở 10%.
- **Histogram** được bật cho ba timer cần phân vị: `http.server.requests`, `ledgerly.posting.lock.wait`, `jdbc.query`.
- **Dashboard là file trong repo** (`infra/grafana/dashboards/ledgerly.json`), nạp bằng provisioning.

Java agent bị loại vì nó nằm ngoài build và không test được cùng code. Bộ ba service rời bị loại vì tốn cấu hình mà không cho thêm gì ở quy mô này.

## Hệ quả

- **Tích cực:** một lần chuyển tiền là một trace xuyên HTTP, SQL, relay, Kafka và consumer (B1). Điều đó có test tự động, không cần Grafana (B2). Metric nghiệp vụ, metric của Spring và trace đi chung một đường. Không có collector thì ứng dụng vẫn chạy bình thường.
- **Tiêu cực / đánh đổi:**
  - **Log chưa lên Loki.** Boot 4.1 có sẵn exporter OTLP cho log nhưng không có cầu nối từ Logback, phải thêm thư viện ngoài. Log trên console đã có `traceId` và `spanId`, nên tìm theo trace vẫn làm được bằng tay.
  - **Chi phí của việc đo không nhỏ:** gần 40% CPU phần Java của ứng dụng lúc quá tải (B4), mà CPU của ứng dụng lại là thứ chạm trần trước ([benchmarks §5](../benchmarks.md#5-tăng-tải-thứ-gì-gãy-trước)). Một lần chuyển tiền mở 12 observation SQL. Nếu cần CPU, `jdbc.includes` và histogram của `jdbc.query` là hai chỗ vặn đầu tiên. Quyết định này chấp nhận chi phí đó để đổi lấy việc nhìn thấy từng câu SQL, và chưa đo được tắt đi thì lợi bao nhiêu.
  - **Thêm một thư viện ngoài BOM** (`datasource-micrometer`), phải tự theo dõi phiên bản khi nâng Boot.
  - **Trace của một lần chuyển kéo dài tới khi consumer xử lý xong**, tức gồm cả độ trễ của relay. Khi Kafka sập, span `outbox publish` chỉ xuất hiện sau khi Kafka trở lại.
  - **Phân vị trên dashboard tính từ bucket** phía server. Số để báo cáo là số của bộ sinh tải, vì nó gồm cả mạng và thời gian request chờ được nhận.
- **`mock-bank` có starter nhưng chưa có gì để đo**: nó chưa có nghiệp vụ tới tuần 9.
- **Image LGTM là `0.35.0`, ghim trong compose.** Tên metric trên Prometheus do collector của image này đặt (ví dụ `http_server_requests_milliseconds_bucket`). Nâng image có thể đổi tên và làm dashboard trống.
- **Cần theo dõi:** dung lượng RAM của container LGTM khi lấy mẫu 100%, số span mỗi request (hiện 18) khi thêm tính năng, và phép so sánh còn nợ: bật so với tắt observation SQL, đo đúng cách.

## Bằng chứng

**B1. Một trace thật** (lấy từ Tempo qua API của Grafana, hai ứng dụng chạy bằng `java -jar`, lần chuyển tiền đầu tiên sau khi khởi động nên thời gian còn gồm cả khởi động nóng):

```text
18 span, 2 service
  +    0.0 ms  ledger-app             SERVER    http post /v1/transfers          28.0 ms
  +    8.5 ms  ledger-app             CLIENT    query  (12 span, mỗi span 0,3 đến 1,1 ms)
  +  112.1 ms  ledger-app             INTERNAL  outbox publish                  222.3 ms
  +  121.2 ms  ledger-app             PRODUCER  ledgerly.transfers.v1 send      213.6 ms
  +  397.0 ms  notification-consumer  CONSUMER  ledgerly.transfers.v1 process    47.8 ms
  +  433.3 ms  notification-consumer  CLIENT    query  (2 span)
```

Một lần chuyển tiền qua API là **12 câu SQL**. Con số này lần đầu được nhìn thấy ở đây.

**B2. Test tự động.** `TracePropagationIT` gửi một request có sẵn `traceparent`, rồi khẳng định: dòng outbox lưu đúng trace id đó, record trên Kafka mang header `traceparent` cùng trace id, và các span thu được (bằng exporter trong bộ nhớ) có `SERVER`, `PRODUCER`, `outbox publish` và `query` trong cùng một trace, với `outbox publish` là con của một span trong trace. `TraceContinuationIT` phía consumer gửi một record có `traceparent` và khẳng định span `CONSUMER` cùng hai span `query` thuộc trace đó. Cả hai test đỏ trước khi có code.

**B3. Metric.** Sau 300 lần chuyển và một lần bị từ chối, Prometheus trong LGTM có `ledgerly_transfers_total{outcome="completed"} = 301` và `{outcome="insufficient_funds"} = 1`. `TransferMetricsIT` khẳng định từng outcome được đếm riêng, ba lần gửi cùng `Idempotency-Key` chỉ được đếm một lần, và một lần chuyển bị rollback không được đếm (counter tăng sau khi commit). Sau ba lượt baseline, `ledgerly_transfers_total{outcome="completed"}` bằng đúng số giao dịch chuyển tiền trong database. Cả 18 truy vấn của dashboard đều hợp lệ và trả về chuỗi số liệu.

**B4. Chi phí của việc đo.** Ba dữ kiện, chi tiết ở [benchmarks §6](../benchmarks.md#6-chi-phí-của-việc-xuất-metric-và-trace):

- **Tắt xuất OTLP, một lượt ở 300 request mỗi giây:** CPU của `ledger-app` là 0,32 nhân, so với 0,32 đến 0,36 nhân khi bật. Không phân biệt được. p99 là 12,90 ms so với 17,01 đến 17,38 ms: có dấu hiệu, nhưng chỉ một lượt.
- **Profile JFR lúc quá tải:** 38,9% mẫu CPU phần Java nằm trong code đo đạc. Khoản lớn nhất là observation cho từng câu SQL (13,6%), rồi tracing (10,8%) và Observation API (9,4%). Code của Ledgerly chiếm 1,8%.
- **Tắt observation của SQL:** phép thử không kết luận được, vì lượt so sánh khác lượt gốc ở bốn điểm.

Tức là **gửi đi thì rẻ, đo trong tiến trình thì không**. Chưa có con số cho việc tắt bớt.

**B5. Tài liệu.** Spring Boot 4.1.1, *Observability* (tên thuộc tính cấu hình, và việc Boot không bắc cầu log sang OTLP). README của `grafana/docker-otel-lgtm` (đường dẫn provisioning cho dashboard).
