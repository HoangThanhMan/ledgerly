# Tuần 7: Observability và benchmark baseline

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 16/11 – 22/11/2026 | 3: MVP | | 13 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

1. **Nhìn thấy** hệ thống đang làm gì: metrics, traces xuyên Kafka, logs có `traceId`.
2. Có **con số hiệu năng đầu tiên** được đo đúng phương pháp, làm baseline cho mọi so sánh sau này.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W07-01 | Compose profile `observability`: `grafana/otel-lgtm`. Thêm `spring-boot-starter-opentelemetry` vào 3 app | 2 | |
| W07-02 | Metric nghiệp vụ (Micrometer): `transfers`, `idempotency.replays`, `outbox.pending`, `outbox.oldest.age`, `posting.lock.wait` | 2 | |
| W07-03 | Trace xuyên Kafka: bật observation cho `KafkaTemplate` và listener, kiểm tra `traceparent` trong header | 1 | |
| W07-04 | Dashboard Grafana (provisioning JSON trong repo): RPS, p50/p95/p99, lỗi, outbox lag, Hikari pending | 2 | `infra/grafana/dashboards/ledgerly.json` |
| W07-05 | k6 `transfer-constant-rate.js`: `setup()` tạo 1.000 ví và nạp tiền. Executor `constant-arrival-rate`, có thresholds | 3 | |
| W07-06 | `docs/benchmarks.md`: phương pháp, mẫu mô tả môi trường. Chạy baseline 3 lần, lấy trung vị | 2 | `perf/results/2026-11-2x-baseline/` |
| W07-07 | **ADR-0007**: OTel qua Boot starter + LGTM (so với Java agent, Prometheus/Jaeger rời) | 1 | |

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

- [ ] `docker compose --profile observability up` mở được Grafana tại `:3000`, có dashboard sẵn
- [ ] `docs/benchmarks.md` có bảng baseline đầu tiên với đủ thông tin môi trường
- [ ] ADR-0007 Accepted
- [ ] Ảnh chụp dashboard lưu trong `docs/images/`

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
