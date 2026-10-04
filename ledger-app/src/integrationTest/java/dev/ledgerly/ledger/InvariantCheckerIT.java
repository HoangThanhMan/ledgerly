package dev.ledgerly.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checker must report a broken ledger, otherwise a green concurrency test proves nothing. Each broken state is
 * created inside a transaction that is rolled back, so other tests never see it.
 */
class InvariantCheckerIT extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");

    private final LedgerApi ledger;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final InvariantChecker checker;

    InvariantCheckerIT(
            @Autowired LedgerApi ledger, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate transaction) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.checker = new InvariantChecker(jdbc);
    }

    @Test
    void ledgerWrittenThroughTheApiIsSound() {
        UUID wallet = fundedWallet(500);
        UUID other = ledger.openAccount(VND).id();
        ledger.post(new PostingRequest(
                TransactionType.TRANSFER,
                null,
                List.of(new Posting(wallet, Money.of(-200, VND)), new Posting(other, Money.of(200, VND)))));

        assertThat(checker.violations()).isEmpty();
    }

    @Test
    void balanceThatDriftedFromItsEntriesViolatesI3AndI4() {
        UUID wallet = fundedWallet(500);

        Map<String, List<Map<String, @Nullable Object>>> violations = inRolledBackTransaction(() -> {
            jdbc.sql("UPDATE accounts SET balance = balance + 7 WHERE id = :id")
                    .param("id", wallet)
                    .update();
            return checker.violations();
        });

        assertThat(violations).containsOnlyKeys("I3", "I4");
        assertThat(violations.get("I3"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("id", wallet)
                        .containsEntry("balance", 507L)
                        .containsKey("entries_sum"));
        assertThat(violations.get("I4")).hasSize(1);
        assertThat(checker.violations()).isEmpty();
    }

    @Test
    void transactionWhoseEntriesDoNotSumToZeroViolatesI1() {
        UUID wallet = fundedWallet(500);

        Map<String, List<Map<String, @Nullable Object>>> violations = inRolledBackTransaction(() -> {
            // The database would reject this at COMMIT (deferred constraint trigger). Until then it is visible here.
            UUID transactionId = jdbc.sql("INSERT INTO ledger_transactions (type) VALUES ('TRANSFER') RETURNING id")
                    .query(UUID.class)
                    .single();
            jdbc.sql("INSERT INTO entries (transaction_id, account_id, amount, balance_after)"
                            + " VALUES (:transactionId, :accountId, 9, 509)")
                    .param("transactionId", transactionId)
                    .param("accountId", wallet)
                    .update();
            return checker.violations();
        });

        assertThat(violations).containsKey("I1");
        assertThat(violations.get("I1")).hasSize(1);
    }

    private <T> T inRolledBackTransaction(Supplier<T> action) {
        return Objects.requireNonNull(transaction.execute(status -> {
            status.setRollbackOnly();
            return action.get();
        }));
    }

    private UUID fundedWallet(long amount) {
        UUID wallet = ledger.openAccount(VND).id();
        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);
        ledger.post(new PostingRequest(
                TransactionType.DEPOSIT,
                null,
                List.of(new Posting(funding, Money.of(-amount, VND)), new Posting(wallet, Money.of(amount, VND)))));
        return wallet;
    }
}
