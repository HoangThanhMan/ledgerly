package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.ledger.AccountView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A wallet and its current balance.")
record WalletResponse(
        @Schema(description = "Id of the wallet.", example = "01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10")
        UUID id,

        @Schema(description = "ISO 4217 code of the wallet's currency.", example = "VND")
        String currency,

        @Schema(
                description =
                        "Current balance, as a string holding a non-negative integer in the minor unit of the currency.",
                example = "350000")
        String balance,

        @Schema(description = "When the wallet was opened.") Instant createdAt) {

    static WalletResponse from(AccountView wallet) {
        return new WalletResponse(
                wallet.id(),
                ApiFormats.currency(wallet.balance()),
                ApiFormats.amount(wallet.balance()),
                wallet.createdAt());
    }
}
