package dev.ledgerly.ticketing.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Tickets to buy, and the wallet to pay with.")
record PurchaseRequest(
        @Schema(description = "The ticket class to buy from.") @NotNull UUID ticketClassId,

        @Schema(description = "One of the caller's wallets. The tickets are paid from it.") @NotNull UUID walletId,

        @Schema(description = "Number of tickets, from 1 to 4.", example = "2") @NotNull Integer quantity) {}
