package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.application.EventPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of the events that have not started yet, soonest first.")
record EventPageResponse(
        @Schema(description = "The events of this page.") List<EventSummaryResponse> items,

        @Schema(description = "Number of this page, from 0.", example = "0")
        int page,

        @Schema(description = "Largest number of events in a page.", example = "20")
        int size,

        @Schema(description = "Number of events on all pages together.", example = "42")
        long totalItems) {

    static EventPageResponse from(EventPage page) {
        return new EventPageResponse(
                page.items().stream().map(EventSummaryResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems());
    }
}
