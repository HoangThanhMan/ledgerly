package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.TransferView;
import java.time.Instant;
import java.util.UUID;

record TransferResponse(
        UUID id,
        String status,
        UUID sourceWalletId,
        UUID targetWalletId,
        String amount,
        String currency,
        Instant createdAt) {

    // A transfer is written in one database transaction, so one that exists is complete.
    private static final String COMPLETED = "COMPLETED";

    static TransferResponse from(TransferView transfer) {
        return new TransferResponse(
                transfer.id(),
                COMPLETED,
                transfer.sourceWalletId(),
                transfer.targetWalletId(),
                ApiFormats.amount(transfer.amount()),
                ApiFormats.currency(transfer.amount()),
                transfer.createdAt());
    }
}
