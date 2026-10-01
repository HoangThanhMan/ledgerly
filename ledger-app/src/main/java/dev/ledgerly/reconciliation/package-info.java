/**
 * Đối soát sổ cái với sao kê ngân hàng: phát hiện chênh lệch và tự xử lý giao dịch ở trạng thái UNKNOWN.
 *
 * <p>Tuần triển khai: 11. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
@NullMarked
package dev.ledgerly.reconciliation;

import org.jspecify.annotations.NullMarked;
