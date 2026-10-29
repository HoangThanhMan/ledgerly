package dev.ledgerly.ticketing.internal.persistence;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tickets left in each class. Every statement here is explicit SQL, never JPA. */
@Repository
public class TicketInventory {

    private record Remaining(UUID ticketClassId, int remaining) {}

    private final JdbcClient jdbc;

    TicketInventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Remaining tickets of every class of the event, by ticket class id. Empty for an unknown event. */
    public Map<UUID, Integer> remainingOf(UUID eventId) {
        return jdbc
                .sql("SELECT id, remaining FROM ticket_classes WHERE event_id = :event")
                .param("event", eventId)
                .query((rs, row) -> new Remaining(rs.getObject("id", UUID.class), rs.getInt("remaining")))
                .stream()
                .collect(Collectors.toUnmodifiableMap(Remaining::ticketClassId, Remaining::remaining));
    }
}
