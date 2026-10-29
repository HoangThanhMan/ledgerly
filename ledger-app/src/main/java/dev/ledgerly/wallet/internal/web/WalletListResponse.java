package dev.ledgerly.wallet.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The caller's wallets.")
record WalletListResponse(
        @Schema(description = "The wallets, oldest first.") List<WalletResponse> items) {}
