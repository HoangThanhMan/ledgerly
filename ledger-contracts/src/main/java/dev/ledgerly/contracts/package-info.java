/**
 * Hợp đồng sự kiện (event contracts) giữa {@code ledger-app} và các consumer.
 *
 * <p>Chỉ chứa record thuần Java: envelope, payload sự kiện và tên topic. Không phụ thuộc Spring hay Jackson
 * để mọi consumer đều dùng được. Quy tắc tiến hóa schema: xem {@code docs/01-kien-truc.md} mục 9.
 */
@NullMarked
package dev.ledgerly.contracts;

import org.jspecify.annotations.NullMarked;
