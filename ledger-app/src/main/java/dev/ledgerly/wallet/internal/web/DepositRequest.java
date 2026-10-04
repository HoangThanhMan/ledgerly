package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Currency;
import java.util.UUID;

record DepositRequest(
        @NotNull UUID walletId,

        @NotNull @Pattern(regexp = ApiFormats.AMOUNT_PATTERN, message = ApiFormats.AMOUNT_MESSAGE) String amount,

        @NotNull Currency currency) {

    Money money() {
        return Money.parsePositive(amount, currency);
    }
}
