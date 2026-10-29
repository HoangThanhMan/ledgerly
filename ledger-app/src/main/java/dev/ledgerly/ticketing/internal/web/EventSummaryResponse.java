package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.application.EventSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An event in the event list.")
record EventSummaryResponse(
        @Schema(description = "Id of the event.") UUID id,

        @Schema(description = "Name of the event.", example = "Ledgerly Live")
        String name,

        @Schema(description = "When the event starts.", example = "2027-03-01T12:00:00Z")
        Instant startsAt,

        @Schema(description = "Price of the cheapest ticket, as a string of VND.", example = "300000")
        String minPrice) {

    static EventSummaryResponse from(EventSummary event) {
        return new EventSummaryResponse(event.id(), event.name(), event.startsAt(), Long.toString(event.minPrice()));
    }
}
