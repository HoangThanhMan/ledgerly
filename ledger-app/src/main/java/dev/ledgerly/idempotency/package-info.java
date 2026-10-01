/**
 * Xử lý {@code Idempotency-Key} hai pha (claim rồi complete): replay, xung đột body, khóa đang xử lý, phục hồi sau crash.
 *
 * <p>Tuần triển khai: 5. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
package dev.ledgerly.idempotency;
