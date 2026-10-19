package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Currency;
import java.util.UUID;

@Schema(description = "The transfer to make.")
record TransferRequest(
        @Schema(
                description = "Wallet the money leaves. It must hold at least the amount.",
                example = "01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10")
        @NotNull UUID sourceWalletId,

        @Schema(
                description = "Wallet the money goes to. Must differ from the source wallet.",
                example = "01a11ad9-7ec9-7c41-8e2a-5b7d9c0e4f21")
        @NotNull UUID targetWalletId,

        @Schema(
                description =
                        "Amount to move, as a string holding a positive integer in the minor unit of the currency. No sign, no leading zeros, no decimals, at most 18 digits.",
                example = "150000")
        @NotNull @Pattern(regexp = ApiFormats.AMOUNT_PATTERN, message = ApiFormats.AMOUNT_MESSAGE) String amount,

        @Schema(
                description =
                        "ISO 4217 code of the currency. Must be the currency of both wallets. Only VND is supported.",
                example = "VND")
        @NotNull Currency currency) {

    Money money() {
        return Money.parsePositive(amount, currency);
    }
}
