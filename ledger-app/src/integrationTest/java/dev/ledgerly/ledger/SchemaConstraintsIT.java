package dev.ledgerly.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Database-level constraints of the ledger: the last line of defense if the Java code has a bug.
 *
 * <p>Uses plain JDBC to control exactly when each commit happens. Rows in {@code entries} cannot be deleted, so each
 * test creates its own accounts and never cleans up.
 */
class SchemaConstraintsIT extends AbstractIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED_BY_TRIGGER = "P0001";

    private final DataSource dataSource;

    SchemaConstraintsIT(@Autowired DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Transactions must balance, checked at commit ---

    @Test
    void balancedTransactionIsCommitted() throws SQLException {
        UUID source = createWallet(1_000);
        UUID target = createWallet(0);

        UUID transaction = commitTransfer(source, target, 100);

        assertThat(queryLongs("SELECT amount FROM entries WHERE transaction_id = ? ORDER BY amount", transaction))
                .containsExactly(-100L, 100L);
    }

    @Test
    void unbalancedTransactionIsRejectedAtCommit() throws SQLException {
        UUID wallet = createWallet(0);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID transaction = insertTransaction(connection);
            // The INSERT succeeds: the constraint is DEFERRABLE INITIALLY DEFERRED and only checked at COMMIT.
            insertEntry(connection, transaction, wallet, 100);

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is not balanced")
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(RAISED_BY_TRIGGER);
        }
    }

    @Test
    void transactionWithoutEntriesIsRejectedAtCommit() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertTransaction(connection);

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("has no entries");
        }
    }

    @Test
    void zeroAmountEntryIsRejected() throws SQLException {
        UUID wallet = createWallet(0);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID transaction = insertTransaction(connection);

            assertCheckViolation(() -> insertEntry(connection, transaction, wallet, 0), "entries_amount_non_zero");
            connection.rollback();
        }
    }

    // --- The ledger is append-only ---

    @Test
    void updatingEntryIsRejected() throws SQLException {
        UUID transaction = commitTransfer(createWallet(1_000), createWallet(0), 100);

        assertAppendOnly(() -> execute("UPDATE entries SET amount = -amount WHERE transaction_id = ?", transaction));
    }

    @Test
    void deletingEntryIsRejected() throws SQLException {
        UUID transaction = commitTransfer(createWallet(1_000), createWallet(0), 100);

        assertAppendOnly(() -> execute("DELETE FROM entries WHERE transaction_id = ?", transaction));
    }

    @Test
    void truncatingEntriesIsRejected() throws SQLException {
        commitTransfer(createWallet(1_000), createWallet(0), 100);
        try (Connection connection = dataSource.getConnection()) {
            // Runs in a transaction that is always rolled back, so a broken constraint cannot wipe other tests' data.
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("TRUNCATE entries")) {
                assertAppendOnly(statement::execute);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void updatingLedgerTransactionIsRejected() throws SQLException {
        UUID transaction = commitTransfer(createWallet(1_000), createWallet(0), 100);

        assertAppendOnly(
                () -> execute("UPDATE ledger_transactions SET reference = 'edited' WHERE id = ?", transaction));
    }

    // --- Balances cannot go negative ---

    @Test
    void negativeBalanceIsRejected() {
        assertCheckViolation(() -> insertAccount("USER_WALLET", null, "VND", -1, false), "balance_non_negative");
    }

    @Test
    void systemAccountMayGoNegative() throws SQLException {
        UUID account = insertAccount("SYSTEM", "test:" + UUID.randomUUID(), "VND", -1, true);

        assertThat(queryLongs("SELECT balance FROM accounts WHERE id = ?", account))
                .containsExactly(-1L);
    }

    @Test
    void userWalletCannotAllowNegative() {
        assertCheckViolation(
                () -> insertAccount("USER_WALLET", null, "VND", 0, true), "allow_negative_only_for_system");
    }

    // --- Currency must be an ISO 4217 code ---

    @Test
    void lowercaseCurrencyIsRejected() {
        assertCheckViolation(() -> insertAccount("USER_WALLET", null, "vnd", 0, false), "currency_iso_4217");
    }

    // --- Seeded system accounts ---

    @Test
    void systemAccountsAreSeeded() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT code, currency, allow_negative FROM accounts"
                                + " WHERE type = 'SYSTEM' AND code LIKE 'system:%' ORDER BY code");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1) + " " + result.getString(2) + " " + result.getBoolean(3));
            }
        }

        assertThat(rows)
                .containsExactly(
                        "system:bank-settlement VND true",
                        "system:funding VND true",
                        "system:withdrawal-suspense VND false");
    }

    // --- helpers ---

    private interface SqlAction {
        void run() throws SQLException;
    }

    private static void assertCheckViolation(SqlAction action, String constraint) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    private static void assertAppendOnly(SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only")
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(RAISED_BY_TRIGGER);
    }

    private UUID createWallet(long balance) throws SQLException {
        return insertAccount("USER_WALLET", null, "VND", balance, false);
    }

    private UUID insertAccount(String type, @Nullable String code, String currency, long balance, boolean allowNegative)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO accounts (type, code, currency, balance, allow_negative)"
                                + " VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            statement.setString(1, type);
            statement.setString(2, code);
            statement.setString(3, currency);
            statement.setLong(4, balance);
            statement.setBoolean(5, allowNegative);
            return singleUuid(statement);
        }
    }

    private UUID commitTransfer(UUID source, UUID target, long amount) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID transaction = insertTransaction(connection);
            insertEntry(connection, transaction, source, -amount);
            insertEntry(connection, transaction, target, amount);
            connection.commit();
            return transaction;
        }
    }

    private static UUID insertTransaction(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO ledger_transactions (type) VALUES ('TEST') RETURNING id")) {
            return singleUuid(statement);
        }
    }

    private static void insertEntry(Connection connection, UUID transaction, UUID account, long amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO entries (transaction_id, account_id, amount, balance_after) VALUES (?, ?, ?, 0)")) {
            statement.setObject(1, transaction);
            statement.setObject(2, account);
            statement.setLong(3, amount);
            statement.executeUpdate();
        }
    }

    private void execute(String sql, UUID parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }

    private List<Long> queryLongs(String sql, UUID parameter) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getLong(1));
                }
            }
        }
        return values;
    }

    private static UUID singleUuid(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, UUID.class);
        }
    }
}
