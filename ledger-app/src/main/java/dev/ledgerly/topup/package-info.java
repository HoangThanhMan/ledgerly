/**
 * Saga nạp tiền và rút tiền qua ngân hàng: máy trạng thái, worker, webhook, bút toán bù trừ.
 *
 * <p>Tuần triển khai: 9. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
@NullMarked
package dev.ledgerly.topup;

import org.jspecify.annotations.NullMarked;
