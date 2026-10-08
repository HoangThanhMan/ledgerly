# Tuần 7: Observability và benchmark baseline

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 16/11 – 22/11/2026 | 3: MVP | | 13 giờ | 🟡 Xong, chờ merge (PR #106–#110, làm ngày 08/10/2026) |

## Mục tiêu

1. **Nhìn thấy** hệ thống đang làm gì: metrics, traces xuyên Kafka, logs có `traceId`.
2. Có **con số hiệu năng đầu tiên** được đo đúng phương pháp, làm baseline cho mọi so sánh sau này.

## Công việc

| ID | Việc | Giờ | Đầu ra | Trạng thái |
|---|---|:-:|---|---|
| W07-01 | Compose profile `observability`: `grafana/otel-lgtm`. Thêm `spring-boot-starter-opentelemetry` vào 3 app | 2 | `compose.yaml`, profile Spring `observability` trong `application.yaml` của ba app | 🟡 #106, #108 |
| W07-02 | Metric nghiệp vụ (Micrometer): `transfers`, `idempotency.replays`, `outbox.pending`, `outbox.oldest.age`, `posting.lock.wait` | 2 | Counter `ledgerly.transfers{outcome}` trong `TransferService`. Bốn metric kia có từ tuần 4–6 | 🟡 #107 |
| W07-03 | Trace xuyên Kafka: bật observation cho `KafkaTemplate` và listener, kiểm tra `traceparent` trong header | 1 | `OutboxService`, `KafkaEventPublisher`, `TracePropagationIT`, `TraceContinuationIT` | 🟡 #106 |
| W07-04 | Dashboard Grafana (provisioning JSON trong repo): RPS, p50/p95/p99, lỗi, outbox lag, Hikari pending | 2 | `infra/grafana/dashboards/ledgerly.json` | 🟡 #108 |
| W07-05 | k6 `transfer-constant-rate.js`: `setup()` tạo 1.000 ví và nạp tiền. Executor `constant-arrival-rate`, có thresholds | 3 | `perf/k6/transfer-constant-rate.js`, `perf/run-baseline.sh`, `perf/compact-raw.py` | 🟡 #109 |
| W07-06 | `docs/benchmarks.md`: phương pháp, mẫu mô tả môi trường. Chạy baseline 3 lần, lấy trung vị | 2 | [benchmarks.md](../benchmarks.md), `perf/results/2026-10-08-baseline/` | 🟡 #110 |
| W07-07 | **ADR-0007**: OTel qua Boot starter + LGTM (so với Java agent, Prometheus/Jaeger rời) | 1 | [ADR-0007](../adr/0007-opentelemetry-qua-boot-starter-va-grafana-lgtm.md) | 🟡 #110 |

## Ghi chú kỹ thuật

### Kịch bản k6

```javascript
export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',  // vòng mở: tránh coordinated omission
      rate: 300, timeUnit: '1s',          // 300 request mỗi giây, BẤT KỂ server nhanh hay chậm
      duration: '5m',
      preAllocatedVUs: 200, maxVUs: 1000,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.001'],
    'http_req_duration{name:transfer}': ['p(95)<150', 'p(99)<200'],
    'dropped_iterations': ['count==0'],   // k6 không theo kịp tốc độ thì kết quả vô nghĩa
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],  // k6 không in p99 nếu không yêu cầu
};
```

Mỗi request dùng một `Idempotency-Key` ngẫu nhiên mới. Có thêm **5%** request cố ý lặp key để đo đường replay.

### Quy tắc benchmark (chi tiết trong [04-chien-luoc-kiem-thu §6](../04-chien-luoc-kiem-thu.md#6-phương-pháp-benchmark))

- Ghi: CPU, RAM, OS, phiên bản JDK, cờ JVM, kích thước dữ liệu, thời gian warm-up, k6 chạy cùng máy hay khác máy.
- Warm-up 1 phút (không tính), đo 5 phút, chạy 3 lần, báo cáo trung vị.
- **Chạy `scripts/invariants.sql` sau mỗi lần chạy.** Nhanh mà sai thì kết quả không có giá trị.
- Commit cả `summary.json` lẫn file kết quả thô.

### Tìm điểm gãy (tùy chọn nếu còn thời gian)

Tăng `rate` theo bậc 100 → 200 → … cho tới khi p99 vượt SLO hoặc có `dropped_iterations`. Ghi lại **thứ gì gãy trước** (CPU, connection pool, khóa) dựa trên dashboard.

## Kiểm thử bắt buộc

| Kiểm tra | Khẳng định |
|---|---|
| Thủ công trên Grafana | Thấy được một trace đi từ `POST /v1/transfers` → SQL → outbox relay → Kafka → consumer |
| k6 baseline | Thresholds đạt, hoặc nếu không đạt thì ghi rõ lý do và điều chỉnh SLO có căn cứ |
| Bất biến sau k6 | `invariants.sql` sạch |

## Definition of Done

- [x] `docker compose --profile observability up` mở được Grafana tại `:3000`, có dashboard sẵn (11 panel, là trang chủ)
- [x] `docs/benchmarks.md` có bảng baseline đầu tiên với đủ thông tin môi trường (p50 2,17 ms, p95 4,21 ms, p99 17,14 ms ở 300 request mỗi giây, [bảng](../benchmarks.md#4-baseline-300-request-mỗi-giây))
- [x] ADR-0007 Accepted (do AI soạn và đặt trạng thái)
- [x] Ảnh chụp dashboard lưu trong `docs/images/` ([dashboard-tuan-07.png](../images/dashboard-tuan-07.png), chụp trong bài tăng tải)

## Kiểm thử bắt buộc: kết quả

| Kiểm tra | Kết quả |
|---|---|
| Một trace từ `POST /v1/transfers` → SQL → relay → Kafka → consumer | Có, 18 span qua hai service, xem trên Tempo. Ngoài kiểm tra tay còn có `TracePropagationIT` và `TraceContinuationIT` |
| k6 baseline, thresholds đạt | Đạt ở lượt 1 và 3. Lượt 2 trượt `dropped_iterations` vì một đợt đứng **trong warm-up**, pha đo nguyên vẹn. Hai lượt chạy bù bị nhiễu. SLO giữ nguyên. Lý do và số liệu ở [benchmarks §4](../benchmarks.md#4-baseline-300-request-mỗi-giây) |
| Bất biến sau k6 | `invariants.sql` và `invariants-events.sql` trả 0 dòng sau cả 18 lượt đo trong ngày, kể cả các lượt quá tải |

## Ghi chú khi thực hiện (08/10/2026)

- **Khác kế hoạch:** tuần này làm thêm `perf/run-baseline.sh` (chạy N lượt và kiểm tra sổ cái sau mỗi lượt) và `perf/compact-raw.py`. Kết quả nằm ở `perf/results/2026-10-08-baseline/`, không phải `2026-11-2x-baseline/`, vì tuần được làm trước lịch.
- **Counter `ledgerly.transfers` tăng sau khi commit**, không phải lúc service quyết định. Bản đầu sẽ đếm thừa khi transaction của idempotency rollback.
- **Mục "Tìm điểm gãy" đã làm:** trần khoảng 1.560 lần chuyển mỗi giây với 2 nhân, CPU của ứng dụng hết trước, gần 40% CPU đó là đo đạc ([benchmarks §5](../benchmarks.md#5-tăng-tải-thứ-gì-gãy-trước)).
- **Rủi ro "máy local yếu" đã thành sự thật**, nặng hơn dự tính: lần đo đầu hỏng vì chính bài đo, và ngay cả lô chính thức cũng có những đợt server đứng 2 đến 12 giây chưa giải thích được. Việc theo tiếp ở issue [#105](https://github.com/HoangThanhMan/ledgerly/issues/105).
- **Giới hạn CPU bằng `taskset`, không bằng `cpus: 2` của compose**, vì ứng dụng chưa chạy trong container (tuần 8).
- **`ConcurrentTransferIT` được sửa trong tuần này** dù là test của tuần 4: nó đỏ trên CI vì luồng chờ connection quá 30 giây, và observation của SQL (thêm ở tuần này) đẩy nó qua ngưỡng. Chi tiết ở [nhật ký](../journal/2026-W47.md#học-được).

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Máy local yếu, k6 và app tranh CPU | Ghi rõ điều này trong kết quả. Giới hạn CPU cho container app (`cpus: 2`) để kết quả lặp lại được |
| LGTM tốn RAM | Chỉ bật profile `observability` khi cần |

## Câu hỏi phỏng vấn tự luyện

1. Coordinated omission là gì? Vì sao `constant-arrival-rate` tránh được?
2. Vì sao báo cáo p99 mà không báo cáo trung bình?
3. Con số p99 đó đo trên máy nào, với dữ liệu bao nhiêu?
4. Nút cổ chai nằm ở đâu, và **làm sao bạn biết**?
