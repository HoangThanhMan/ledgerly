package dev.ledgerly.wallet.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Currency;

@Schema(description = "The wallet to open.")
record OpenWalletRequest(
        @Schema(description = "ISO 4217 code of the wallet's currency. Only VND is supported.", example = "VND")
        @NotNull Currency currency) {}
