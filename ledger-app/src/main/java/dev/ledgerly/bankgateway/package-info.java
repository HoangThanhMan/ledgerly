/**
 * HTTP client tới mock-bank ({@code @HttpExchange}): timeout, retry có backoff và jitter, ánh xạ kết quả sang sealed type.
 *
 * <p>Tuần triển khai: 9. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
package dev.ledgerly.bankgateway;
