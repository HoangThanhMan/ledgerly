package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.DepositView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A completed deposit.")
record DepositResponse(
        @Schema(
                description = "Id of the deposit. Also the `transactionId` of the entry it wrote to the wallet.",
                example = "01a11ad9-7f10-7459-a273-deb24dbb4296")
        UUID id,

        @Schema(
                description = "Always `COMPLETED`: a deposit that exists has credited the wallet.",
                allowableValues = "COMPLETED")
        String status,

        @Schema(description = "Wallet that was credited.", example = "01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10")
        UUID walletId,

        @Schema(
                description =
                        "Amount credited, as a string holding a positive integer in the minor unit of the currency.",
                example = "500000")
        String amount,

        @Schema(description = "ISO 4217 code of the currency.", example = "VND")
        String currency,

        @Schema(description = "When the deposit was committed.")
        Instant createdAt) {

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
