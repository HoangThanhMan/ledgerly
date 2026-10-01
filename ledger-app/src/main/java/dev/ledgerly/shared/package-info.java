/**
 * Kiểu dùng chung giữa các module: {@code Money}, định danh, Problem Details (RFC 9457), request context (ScopedValue).
 *
 * <p>Tuần triển khai: 3. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
@NullMarked
package dev.ledgerly.shared;

import org.jspecify.annotations.NullMarked;
