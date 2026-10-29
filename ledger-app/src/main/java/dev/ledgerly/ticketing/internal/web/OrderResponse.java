package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.persistence.OrderRepository.OrderRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A ticket order. It exists only once it is paid: there is no pending order.")
record OrderResponse(
        @Schema(description = "Id of the order.") UUID id,

        @Schema(description = "The event the tickets are for.")
        UUID eventId,

        @Schema(description = "The ticket class the tickets are of.")
        UUID ticketClassId,

        @Schema(description = "The wallet the tickets were paid from.")
        UUID walletId,

        @Schema(description = "Number of tickets.", example = "2")
        int quantity,

        @Schema(description = "Price paid for all the tickets, as a string of minor units.", example = "1000000")
        String amount,

        @Schema(description = "ISO 4217 code of the currency.", example = "VND")
        String currency,

        @Schema(description = "The ledger transaction that paid for the order.")
        UUID ledgerTransactionId,

        @Schema(description = "When the order was paid.") Instant createdAt) {

    static OrderResponse from(OrderRow order) {
        return new OrderResponse(
                order.id(),
                order.eventId(),
                order.ticketClassId(),
                order.walletId(),
                order.quantity(),
                Long.toString(order.amount()),
                "VND",
                order.ledgerTransactionId(),
                order.createdAt());
    }
}
