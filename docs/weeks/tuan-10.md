# Tuần 10: Chaos engineering

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 07/12 – 13/12/2026 | 4: Mở rộng | | 13 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Biến câu "hệ thống chịu lỗi tốt" thành **một bảng kết quả** mà ai cũng chạy lại được: 5 kịch bản lỗi, mỗi kịch bản có cách chèn lỗi, kỳ vọng và kết quả đo.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W10-01 | Compose profile `chaos`: Toxiproxy, proxy cho `mock-bank` và PostgreSQL (`infra/toxiproxy/toxiproxy.json`) | 1.5 | |
| W10-02 | Testcontainers Toxiproxy trong suite integration: tự động hóa kịch bản 1, 2, 5 | 2.5 | `ChaosBankIT`, `ChaosDatabaseIT` |
| W10-03 | Kịch bản 3 (Kafka sập 60 giây): `pause`/`unpause` container Kafka qua Docker API trong test | 1.5 | `ChaosKafkaOutageIT` |
| W10-04 | Kịch bản 4 (`kill -9` relay): script chạy với compose `full` khi k6 đang tạo tải | 1.5 | `scripts/chaos/kill-relay.sh` |
| W10-05 | Chống quá tải: `@ConcurrencyLimit` ở tầng application, HikariCP `maximumPoolSize` và `connectionTimeout`, trả 503 + `Retry-After` | 2 | |
| W10-06 | JFR khi chạy tải: đếm sự kiện `jdk.VirtualThreadPinned` | 1 | |
| W10-07 | *(Could)* Circuit breaker cho `BankClient` | 1.5 | |
| W10-08 | Bảng chaos trong README + **ADR-0009** (chống quá tải) | 1.5 | |

## Ma trận chaos

| # | Kịch bản | Cách chèn | Kết quả kỳ vọng phải chứng minh | Đo |
|:-:|---|---|---|---|
| 1 | mock-bank chậm 3 giây | Toxiproxy `latency` 3000 ms | Timeout, retry có backoff + jitter, top-up `PENDING` rồi `SETTLED` hoặc `UNKNOWN`, sau đó đối soát xử lý | Số lần retry, thời gian tới trạng thái cuối |
| 2 | Mất kết nối tới mock-bank | `bandwidth = 0` / `reset_peer` | Không trừ tiền hai lần. Rút tiền bù trừ đúng | Số giao dịch trùng = 0 |
| 3 | Kafka sập 60 giây | `docker pause kafka` | API vẫn trả 201. Outbox tồn đọng rồi xả hết khi Kafka trở lại. Không mất sự kiện | `outbox.pending` cao nhất, thời gian xả hết |
| 4 | Relay bị `kill -9` giữa lô | `docker kill -s KILL ledger-app` khi đang tải, rồi khởi động lại | Có phát trùng nhưng consumer loại bỏ. 0 sự kiện mất | Số bản trùng, số sự kiện mất = 0 |
| 5 | PostgreSQL chậm 500 ms | Toxiproxy `latency` 500 ms trên proxy DB | `@ConcurrencyLimit` ngăn cạn pool. Quá tải trả 503 nhanh, không treo | p99 của 503, số request treo = 0 |

Sau **mỗi** kịch bản đều chạy `scripts/invariants.sql`.

## Ghi chú kỹ thuật

### Vì sao virtual threads cần giới hạn đồng thời

Platform thread pool (Tomcat 200 thread) vô tình giới hạn số request vào DB cùng lúc. Virtual threads **bỏ giới hạn đó**: 10.000 request có thể cùng chờ connection pool 20 connection. DB chậm (kịch bản 5) thì hàng đợi phình ra, request treo tới timeout. `@ConcurrencyLimit` (Spring Framework 7) đặt lại giới hạn **có chủ đích**, và trả lỗi nhanh khi vượt ngưỡng.

### Pinning ở Java 25

Từ JDK 24 (JEP 491), `synchronized` không còn ghim virtual thread. Vẫn bật JFR để **chứng minh** điều đó với code và thư viện thật của dự án:

```bash
java -XX:StartFlightRecording=filename=pin.jfr,settings=profile ... &
# chạy k6, rồi:
jfr print --events jdk.VirtualThreadPinned pin.jfr | grep -c 'jdk.VirtualThreadPinned'
```

## Definition of Done

- [ ] 5/5 kịch bản có kết quả trong README (kể cả kịch bản **không đạt**, ghi thật kèm hướng xử lý)
- [ ] Kịch bản 1, 2, 3, 5 chạy tự động trong suite integration
- [ ] Bất biến sạch sau mọi kịch bản
- [ ] ADR-0009 Accepted

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Toxiproxy trước Kafka phức tạp (advertised listeners) | Dùng `pause` container thay vì proxy cho Kafka |
| Test chaos chậm, làm CI lâu | Gắn tag `@Tag("chaos")`, chạy trong job CI riêng hoặc theo lịch hằng đêm |

## Câu hỏi phỏng vấn tự luyện

1. Vì sao bật virtual threads có thể làm hệ thống **tệ hơn**?
2. Pinning là gì? JEP 491 đã thay đổi điều gì?
3. Fail-fast (503) tốt hơn chờ lâu ở điểm nào? Client nên làm gì khi nhận 503?
4. Circuit breaker và retry tương tác thế nào? Đặt cái nào ở ngoài?
5. Nếu tải tăng 10 lần thì cái gì vỡ trước?
