package dev.ledgerly.ledger.internal.persistence;

import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.internal.domain.Account;
import dev.ledgerly.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String VIEW_COLUMNS = "id, type, currency, balance, created_at";

    /** SQLSTATE that PostgreSQL raises when {@code lock_timeout} runs out. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AccountView insertUserWallet(Currency currency) {
        return jdbc.sql("INSERT INTO accounts (type, currency) VALUES ('USER_WALLET', :currency) RETURNING "
                        + VIEW_COLUMNS)
                .param("currency", currency.getCurrencyCode())
                .query(AccountRepository::toView)
                .single();
    }

    public Optional<AccountView> findView(UUID id) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::toView)
                .optional();
    }

    public Optional<UUID> findIdByCode(String code) {
        return jdbc.sql("SELECT id FROM accounts WHERE code = :code")
                .param("code", code)
                .query(UUID.class)
                .optional();
    }

    /**
     * Locks the accounts a posting touches until the transaction ends, and returns their current state.
     *
     * <p>The rows are locked in id order. Two postings that share accounts therefore always wait for each other in
     * the same order and cannot deadlock (ADR-0004). {@code FOR NO KEY UPDATE} is the lock an {@code UPDATE} of the
     * balance takes anyway: it excludes other postings but, unlike {@code FOR UPDATE}, does not block inserts of
     * rows that reference the account.
     *
     * <p>Waiting for a lock is bounded by {@code lockTimeout}, set for the current transaction only.
     *
     * @throws CannotAcquireLockException if a lock is still held by another transaction when the timeout runs out
     */
    public List<Account> lockAll(Collection<UUID> ids, Duration lockTimeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
        try {
            return jdbc.sql("SELECT id, currency, balance, allow_negative FROM accounts WHERE id = ANY (:ids)"
                            + " ORDER BY id FOR NO KEY UPDATE")
                    .param("ids", ids.toArray(UUID[]::new))
                    .query((rs, row) -> new Account(
                            rs.getObject("id", UUID.class), money(rs, "balance"), rs.getBoolean("allow_negative")))
                    .list();
        } catch (UncategorizedSQLException e) {
            // Spring does not translate this PostgreSQL state into its lock exceptions.
            SQLException cause = e.getSQLException();
            if (cause != null && LOCK_NOT_AVAILABLE.equals(cause.getSQLState())) {
                throw new CannotAcquireLockException(
                        "accounts " + ids + " stayed locked for longer than " + lockTimeout, e);
            }
            throw e;
        }
    }

    public void updateBalance(UUID id, long balance) {
        int updated = jdbc.sql("UPDATE accounts SET balance = :balance WHERE id = :id")
                .param("balance", balance)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("account " + id + " vanished while posting");
        }
    }

    private static AccountView toView(ResultSet rs, int row) throws SQLException {
        return new AccountView(
                rs.getObject("id", UUID.class),
                AccountType.valueOf(rs.getString("type")),
                money(rs, "balance"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    static Money money(ResultSet rs, String column) throws SQLException {
        return Money.of(rs.getLong(column), Currency.getInstance(rs.getString("currency")));
    }
}
