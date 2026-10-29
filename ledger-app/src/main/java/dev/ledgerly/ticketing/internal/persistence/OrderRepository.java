package dev.ledgerly.ticketing.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
