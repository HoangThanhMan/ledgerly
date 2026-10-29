package dev.ledgerly.ticketing.internal.application;

import java.util.Map;
import java.util.UUID;

/** An event together with the tickets left in each of its classes, as of the moment it was read. */
public record EventDetails(EventView event, Map<UUID, Integer> remaining) {

    public EventDetails {
        remaining = Map.copyOf(remaining);
    }

    public int remainingOf(UUID ticketClassId) {
        Integer left = remaining.get(ticketClassId);
        if (left == null) {
            throw new IllegalArgumentException("ticket class " + ticketClassId + " is not part of event " + event.id());
        }
        return left;
    }
}
