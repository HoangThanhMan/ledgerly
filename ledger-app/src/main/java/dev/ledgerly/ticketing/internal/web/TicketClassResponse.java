package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.application.TicketClassView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A class of tickets of an event.")
record TicketClassResponse(
        @Schema(description = "Id of the ticket class. Orders name it.")
        UUID id,

        @Schema(description = "Name, unique within the event.", example = "VIP")
        String name,

        @Schema(description = "Price of one ticket, as a string of VND.", example = "2000000")
        String price,

        @Schema(description = "VND, the only currency.", example = "VND")
        String currency,

        @Schema(description = "Tickets the class started with.", example = "100")
        int totalQty,

        @Schema(description = "Tickets still for sale when the response was made.", example = "42")
        int remaining) {

    static TicketClassResponse from(TicketClassView ticketClass, int remaining) {
        return new TicketClassResponse(
                ticketClass.id(),
                ticketClass.name(),
                Long.toString(ticketClass.price()),
                "VND",
                ticketClass.totalQty(),
                remaining);
    }
}
