# Tuần 8: Hoàn thiện MVP và phát hành v1.0.0

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 23/11 – 29/11/2026 | 3: MVP | **M4**: MVP `v1.0.0` | 14 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Một người lạ mở repo phải **hiểu dự án trong 30 giây** và **chạy được trong 10 phút**. Tuần này **không thêm tính năng**, chỉ đóng gói, viết tài liệu và sửa lỗi.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W08-01 | OpenAPI bằng springdoc (bản hỗ trợ Boot 4), có mô tả, ví dụ và mã lỗi. Xuất `docs/openapi.yaml` | 2 | Swagger UI |
| W08-02 | Dockerfile multi-stage cho 3 app: layered jar (`-Djarmode=tools extract --layers`), AOT cache Java 25, user non-root | 3 | `*/Dockerfile` |
| W08-03 | Compose profile `full` và `application-compose.yaml`: một lệnh chạy cả hệ thống | 1.5 | |
| W08-04 | README đầy đủ theo cấu trúc bên dưới | 3 | `README.md` |
| W08-05 | Deploy demo: Oracle Always Free (nếu có thẻ) hoặc Render | 2 | Link demo |
| W08-06 | Bug bash: chạy k6 + bất biến + kịch bản tay trên bản `full`, sửa lỗi | 2 | |
| W08-07 | `CHANGELOG.md` (sinh từ Conventional Commits), tag `v1.0.0`, GitHub Release | 0.5 | |

## Ghi chú kỹ thuật

### Dockerfile (khung)

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :ledger-app:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS extract
WORKDIR /builder
COPY --from=build /src/ledger-app/build/libs/ledger-app-*.jar application.jar
# Tách layer: dependencies / spring-boot-loader / snapshot-dependencies / application
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 1001 app
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
# Training run tạo AOT cache (JEP 514/515): khởi động context rồi thoát.
# Phải chạy trong image cuối để cùng JDK và cùng classpath với lúc chạy thật.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar application.jar
USER app
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
```

> Đây là khung minh họa. Khi làm, đối chiếu từng lệnh với tài liệu *Container Images → Dockerfiles* của Spring Boot 4.1. Training run sẽ cố kết nối DB và Kafka, nên cần một profile riêng để tắt những thứ đó, ví dụ `spring.flyway.enabled=false` và tắt relay. Đo thời gian khởi động **có và không có** AOT cache rồi ghi vào `docs/benchmarks.md`.

### Cấu trúc README

1. Pitch một câu + badge (CI, coverage, Java 25, Spring Boot 4.1)
2. GIF demo hoặc ảnh dashboard
3. Phạm vi: làm gì, **không làm gì**
4. Sơ đồ kiến trúc (container) và sơ đồ tuần tự chuyển tiền
5. Quickstart một lệnh
6. Link Swagger và ví dụ `curl`
7. Bằng chứng đúng đắn: bảng bất biến và test tương ứng
8. Benchmark kèm phương pháp
9. Danh sách ADR
10. Giới hạn và hướng phát triển (viết thật lòng)

## Definition of Done

- [ ] Clone repo trên một thư mục sạch, chạy `docker compose --profile full up`, hệ thống sẵn sàng trong dưới 90 giây
- [ ] Mọi mục *Must* trong [00-tong-quan §6](../00-tong-quan-du-an.md#6-phạm-vi-moscow) đã xong
- [ ] Link demo sống, README ghi chú về khởi động lạnh
- [ ] Tag `v1.0.0` có release notes
- [ ] **Mốc M4 đạt**

## Rủi ro và phương án

| Rủi ro | Phương án |
|---|---|
| Free tier không đủ RAM cho Kafka | Bản demo chạy `ledger-app` + Postgres, tắt relay (`ledgerly.outbox.relay.enabled=false`). Ghi rõ trong README |
| AOT cache lỗi trên ARM | Bỏ AOT cache ở bản demo, giữ ở bản local, ghi lại lý do |
| Muốn thêm tính năng mới | **Không.** Ghi vào backlog tuần 9+ |

## Câu hỏi phỏng vấn tự luyện

1. Layered jar giúp gì cho Docker cache?
2. AOT cache của Java 25 làm gì? Đo được cải thiện bao nhiêu?
3. Vì sao chạy container bằng user non-root?
4. Hãy trình bày dự án trong 2 phút (bấm giờ).
