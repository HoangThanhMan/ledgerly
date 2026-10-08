package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.TransferView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A completed transfer.")
record TransferResponse(
        @Schema(
                description = "Id of the transfer. Also the `transactionId` of the two entries it wrote.",
                example = "01a11ad9-7f10-7459-a273-deb24dbb4296")
        UUID id,

        @Schema(
                description = "Always `COMPLETED`: a transfer that exists has moved the money.",
                allowableValues = "COMPLETED")
        String status,

        @Schema(description = "Wallet the money left.", example = "01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10")
        UUID sourceWalletId,

        @Schema(description = "Wallet the money went to.", example = "01a11ad9-7ec9-7c41-8e2a-5b7d9c0e4f21")
        UUID targetWalletId,

        @Schema(
                description = "Amount moved, as a string holding a positive integer in the minor unit of the currency.",
                example = "150000")
        String amount,

        @Schema(description = "ISO 4217 code of the currency.", example = "VND")
        String currency,

        @Schema(description = "When the transfer was committed.")
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
