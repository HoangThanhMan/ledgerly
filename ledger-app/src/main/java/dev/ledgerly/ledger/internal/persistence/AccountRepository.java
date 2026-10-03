package dev.ledgerly.ledger.internal.persistence;

import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.internal.domain.Account;
import dev.ledgerly.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String VIEW_COLUMNS = "id, type, currency, balance, created_at";

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
     * Reads the accounts a posting touches, in id order. Locking them only needs {@code FOR UPDATE} here:
     * the id order already avoids deadlocks between concurrent postings.
     */
    public List<Account> findForPosting(Collection<UUID> ids) {
        return jdbc.sql("SELECT id, currency, balance, allow_negative FROM accounts WHERE id = ANY (:ids) ORDER BY id")
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class), money(rs, "balance"), rs.getBoolean("allow_negative")))
                .list();
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
