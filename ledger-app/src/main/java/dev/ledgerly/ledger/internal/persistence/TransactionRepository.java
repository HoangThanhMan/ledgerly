package dev.ledgerly.ledger.internal.persistence;

import dev.ledgerly.ledger.TransactionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    /** A row of {@code ledger_transactions}, without its entries. */
    public record TransactionRow(
            UUID id, TransactionType type, @Nullable String reference, Instant createdAt) {}

    private final JdbcClient jdbc;

    TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TransactionRow insert(TransactionType type, @Nullable String reference) {
        return jdbc.sql("INSERT INTO ledger_transactions (type, reference) VALUES (:type, :reference)"
                        + " RETURNING id, type, reference, created_at")
                .param("type", type.name())
                .param("reference", reference, Types.VARCHAR)
                .query(TransactionRepository::toRow)
                .single();
    }

    public Optional<TransactionRow> find(UUID id) {
        return jdbc.sql("SELECT id, type, reference, created_at FROM ledger_transactions WHERE id = :id")
                .param("id", id)
                .query(TransactionRepository::toRow)
                .optional();
    }

    private static TransactionRow toRow(ResultSet rs, int row) throws SQLException {
        return new TransactionRow(
                rs.getObject("id", UUID.class),
                TransactionType.valueOf(rs.getString("type")),
                rs.getString("reference"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
