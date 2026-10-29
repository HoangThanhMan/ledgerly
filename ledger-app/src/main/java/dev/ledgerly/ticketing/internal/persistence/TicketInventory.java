package dev.ledgerly.ticketing.internal.persistence;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tickets left in each class. Every statement here is explicit SQL, never JPA.
 *
 * <p>A sale takes its tickets with one conditional {@code UPDATE}: checking that enough are left and taking them is a
 * single atomic step, decided on the newest version of the row. At {@code READ COMMITTED} a sale that had to wait for
 * the row lock evaluates its condition again against what the sale before it committed, so two sales can never both
 * take the last ticket.
 */
@Repository
public class TicketInventory {

    /** SQLSTATE that PostgreSQL raises when {@code lock_timeout} runs out. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private record Remaining(UUID ticketClassId, int remaining) {}

    private final JdbcClient jdbc;
    private final Duration lockTimeout;

    TicketInventory(JdbcClient jdbc, @Value("${ledgerly.ticketing.lock-timeout:2s}") Duration lockTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
    }

    /** The ticket class with what a purchase needs to know about its event, or empty if there is no such class. */
    public Optional<TicketClassSnapshot> snapshot(UUID ticketClassId) {
        return jdbc.sql("""
                        SELECT tc.id, tc.name, tc.price, e.id AS event_id, e.name AS event_name, e.payout_wallet_id,
                               e.starts_at
                        FROM ticket_classes tc JOIN events e ON e.id = tc.event_id
                        WHERE tc.id = :id
                        """)
                .param("id", ticketClassId)
                .query((rs, row) -> new TicketClassSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_name"),
                        rs.getObject("payout_wallet_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Takes {@code quantity} tickets if that many are left. The row stays locked until the transaction ends, so the
     * tickets can still be given back with {@link #release} before anyone else sees them gone.
     *
     * @return the tickets left afterwards, or empty if fewer than {@code quantity} were left and nothing was taken
     * @throws CannotAcquireLockException if another sale held the row for longer than the lock timeout
     */
    public OptionalInt tryReserve(UUID ticketClassId, int quantity) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
        try {
            return jdbc.sql("""
                            UPDATE ticket_classes SET remaining = remaining - :quantity
                            WHERE id = :id AND remaining >= :quantity
                            RETURNING remaining
                            """)
                    .param("id", ticketClassId)
                    .param("quantity", quantity)
                    .query(Integer.class)
                    .optional()
                    .map(OptionalInt::of)
                    .orElseGet(OptionalInt::empty);
        } catch (UncategorizedSQLException e) {
            // Spring does not translate this PostgreSQL state into its lock exceptions.
            SQLException cause = e.getSQLException();
            if (cause != null && LOCK_NOT_AVAILABLE.equals(cause.getSQLState())) {
                throw new CannotAcquireLockException(
                        "ticket class " + ticketClassId + " stayed locked for longer than " + lockTimeout, e);
            }
            throw e;
        }
    }

    /**
     * Gives back tickets that {@link #tryReserve} took in the same transaction, when the purchase is refused after
     * all. The transaction still holds the row lock, so to everyone else the tickets were never gone.
     *
     * @return the tickets left afterwards
     */
    public int release(UUID ticketClassId, int quantity) {
        return jdbc.sql(
                        "UPDATE ticket_classes SET remaining = remaining + :quantity WHERE id = :id RETURNING remaining")
                .param("id", ticketClassId)
                .param("quantity", quantity)
                .query(Integer.class)
                .single();
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
