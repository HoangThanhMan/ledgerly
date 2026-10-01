/**
 * Lõi sổ cái kép: account, ledger transaction, entry bất biến. Ghi bút toán với khóa account theo thứ tự id.
 *
 * <p>Tuần triển khai: 3–4. API công khai nằm ở gốc package này. Mọi thứ trong {@code internal} là riêng tư
 * của module và được ArchUnit kiểm tra.
 */
@NullMarked
package dev.ledgerly.ledger;

import org.jspecify.annotations.NullMarked;
