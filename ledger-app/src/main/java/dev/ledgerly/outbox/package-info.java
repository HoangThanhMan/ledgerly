/**
 * Transactional outbox: ghi sự kiện trong cùng transaction nghiệp vụ, relay lên Kafka bằng {@code FOR UPDATE SKIP LOCKED}.
 *
 * <p>Tuần triển khai: 6. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
package dev.ledgerly.outbox;
