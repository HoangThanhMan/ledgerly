package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Currency;
import java.util.UUID;

@Schema(description = "The deposit to make.")
record DepositRequest(
        @Schema(description = "Wallet to credit.", example = "01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10") @NotNull UUID walletId,

        @Schema(
                description =
                        "Amount to credit, as a string holding a positive integer in the minor unit of the currency. No sign, no leading zeros, no decimals, at most 18 digits.",
                example = "500000")
        @NotNull @Pattern(regexp = ApiFormats.AMOUNT_PATTERN, message = ApiFormats.AMOUNT_MESSAGE) String amount,

        @Schema(
                description =
                        "ISO 4217 code of the currency. Must be the currency of the wallet. Only VND is supported.",
                example = "VND")
        @NotNull Currency currency) {

    Money money() {
        return Money.parsePositive(amount, currency);
    }
}
