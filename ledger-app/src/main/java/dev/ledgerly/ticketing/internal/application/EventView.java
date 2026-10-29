package dev.ledgerly.ticketing.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An event as its organizer set it up. Changes only when the organizer edits it, so it may be cached: what sales
 * change, the remaining tickets, is not part of it.
 *
 * @param classes cheapest first
 */
public record EventView(
        UUID id, UUID organizerId, UUID payoutWalletId, String name, Instant startsAt, List<TicketClassView> classes) {

    public EventView {
        classes = List.copyOf(classes);
    }
}
