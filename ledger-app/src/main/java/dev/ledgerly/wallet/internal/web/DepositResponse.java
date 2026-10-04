package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.DepositView;
import java.time.Instant;
import java.util.UUID;

record DepositResponse(UUID id, String status, UUID walletId, String amount, String currency, Instant createdAt) {

    static DepositResponse from(DepositView deposit) {
        return new DepositResponse(
                deposit.id(),
                "COMPLETED",
                deposit.walletId(),
                ApiFormats.amount(deposit.amount()),
                ApiFormats.currency(deposit.amount()),
                deposit.createdAt());
    }
}
