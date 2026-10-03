package dev.ledgerly.ledger.internal.persistence;

import dev.ledgerly.ledger.EntryView;
import dev.ledgerly.ledger.internal.domain.EntryDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EntryRepository {

    // Entries carry no currency of their own: it belongs to the account (ADR-0003).
    private static final String SELECT_VIEW = "SELECT e.id, e.transaction_id, e.account_id, e.amount,"
            + " e.balance_after, e.created_at, a.currency"
            + " FROM entries e JOIN accounts a ON a.id = e.account_id";

    private final JdbcClient jdbc;

    EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(UUID transactionId, List<EntryDraft> entries) {
        for (EntryDraft entry : entries) {
            jdbc.sql("INSERT INTO entries (transaction_id, account_id, amount, balance_after)"
                            + " VALUES (:transactionId, :accountId, :amount, :balanceAfter)")
                    .param("transactionId", transactionId)
                    .param("accountId", entry.accountId())
                    .param("amount", entry.amount().amount())
                    .param("balanceAfter", entry.balanceAfter().amount())
                    .update();
        }
    }

    /** Keyset pagination on the {@code (account_id, id)} index: no OFFSET, so every page costs the same. */
    public List<EntryView> findByAccountOlderThan(UUID accountId, long olderThan, int limit) {
        return jdbc.sql(SELECT_VIEW + " WHERE e.account_id = :accountId AND e.id < :olderThan"
                        + " ORDER BY e.id DESC LIMIT :limit")
                .param("accountId", accountId)
                .param("olderThan", olderThan)
                .param("limit", limit)
                .query(EntryRepository::toView)
                .list();
    }

    public List<EntryView> findByTransaction(UUID transactionId) {
        return jdbc.sql(SELECT_VIEW + " WHERE e.transaction_id = :transactionId ORDER BY e.id")
                .param("transactionId", transactionId)
                .query(EntryRepository::toView)
                .list();
    }

    private static EntryView toView(ResultSet rs, int row) throws SQLException {
        return new EntryView(
                rs.getLong("id"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                AccountRepository.money(rs, "amount"),
                AccountRepository.money(rs, "balance_after"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
