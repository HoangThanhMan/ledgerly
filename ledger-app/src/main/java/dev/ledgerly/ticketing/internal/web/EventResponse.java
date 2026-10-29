package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.application.EventDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An event with its ticket classes and the tickets left in each.")
record EventResponse(
        @Schema(description = "Id of the event.") UUID id,

        @Schema(description = "Name of the event.", example = "Ledgerly Live")
        String name,

        @Schema(description = "When the event starts.", example = "2027-03-01T12:00:00Z")
        Instant startsAt,

        @Schema(description = "The organizer's wallet that receives the money of the tickets sold.")
        UUID payoutWalletId,

        @Schema(description = "The ticket classes, cheapest first.")
        List<TicketClassResponse> ticketClasses) {

    static EventResponse from(EventDetails details) {
        return new EventResponse(
                details.event().id(),
                details.event().name(),
                details.event().startsAt(),
                details.event().payoutWalletId(),
                details.event().classes().stream()
                        .map(ticketClass ->
                                TicketClassResponse.from(ticketClass, details.remainingOf(ticketClass.id())))
                        .toList());
    }
}
