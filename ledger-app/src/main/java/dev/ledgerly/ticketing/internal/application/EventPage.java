package dev.ledgerly.ticketing.internal.application;

import java.util.List;

/** One page of the event list. */
public record EventPage(List<EventSummary> items, int page, int size, long totalItems) {

    public EventPage {
        items = List.copyOf(items);
    }
}
