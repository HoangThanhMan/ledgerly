package dev.ledgerly.ticketing.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code ticket_orders} table. An order is written once, in the transaction that pays for it, and never changed. */
@Repository
public class OrderRepository {

    /**
     * An order with the event its ticket class belongs to.
     *
     * @param amount minor units of VND
     */
    public record OrderRow(
            UUID id,
            UUID eventId,
            UUID ticketClassId,
            UUID buyerId,
            UUID walletId,
            int quantity,
            long amount,
            UUID ledgerTransactionId,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public OrderRow insert(
            UUID ticketClassId, UUID buyerId, UUID walletId, int quantity, long amount, UUID ledgerTransactionId) {
        return jdbc.sql("""
                        WITH o AS (
                            INSERT INTO ticket_orders
                                (ticket_class_id, buyer_id, wallet_id, quantity, amount, ledger_transaction_id)
                            VALUES (:ticketClass, :buyer, :wallet, :quantity, :amount, :transaction)
                            RETURNING *
                        )
                        SELECT o.*, tc.event_id FROM o JOIN ticket_classes tc ON tc.id = o.ticket_class_id
                        """)
                .param("ticketClass", ticketClassId)
                .param("buyer", buyerId)
                .param("wallet", walletId)
                .param("quantity", quantity)
                .param("amount", amount)
                .param("transaction", ledgerTransactionId)
                .query(OrderRepository::row)
                .single();
    }

    /** A page of the buyer's orders, newest first. */
    public List<OrderRow> page(UUID buyerId, int page, int size) {
        return jdbc.sql("""
                        SELECT o.*, tc.event_id FROM ticket_orders o JOIN ticket_classes tc ON tc.id = o.ticket_class_id
                        WHERE o.buyer_id = :buyer
                        ORDER BY o.created_at DESC, o.id DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("buyer", buyerId)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(OrderRepository::row)
                .list();
    }

    public long countOf(UUID buyerId) {
        return jdbc.sql("SELECT count(*) FROM ticket_orders WHERE buyer_id = :buyer")
                .param("buyer", buyerId)
                .query(Long.class)
                .single();
    }

    /** The order, if it exists and the buyer placed it. */
    public Optional<OrderRow> find(UUID orderId, UUID buyerId) {
        return jdbc.sql("""
                        SELECT o.*, tc.event_id FROM ticket_orders o JOIN ticket_classes tc ON tc.id = o.ticket_class_id
                        WHERE o.id = :id AND o.buyer_id = :buyer
                        """)
                .param("id", orderId)
                .param("buyer", buyerId)
                .query(OrderRepository::row)
                .optional();
    }

    static OrderRow row(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("ticket_class_id", UUID.class),
                rs.getObject("buyer_id", UUID.class),
                rs.getObject("wallet_id", UUID.class),
                rs.getInt("quantity"),
                rs.getLong("amount"),
                rs.getObject("ledger_transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
