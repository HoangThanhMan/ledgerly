package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.DepositResult;
import dev.ledgerly.wallet.internal.application.OpenWalletResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.statistics.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContextManager;

/**
 * Model-based test: random sequences of deposits and transfers are applied both to the real system and to a model
 * that is nothing more than an array of balances. After every command the outcome and all balances must agree.
 *
 * <p>jqwik shrinks a failing sequence to the shortest one that still fails and prints the seed to replay it.
 */
class LedgerModelProperties extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    // The generator in commands() names the wallets 0, 1 and 2.
    private static final int WALLETS = 3;
    /** Index of a wallet that does not exist. */
    private static final int UNKNOWN = WALLETS;

    @Autowired
    @SuppressWarnings("NullAway.Init")
    private WalletService wallets;

    @Autowired
    @SuppressWarnings("NullAway.Init")
    private TransferService transfers;

    @Autowired
    @SuppressWarnings("NullAway.Init")
    private JdbcClient jdbc;

    sealed interface Command {}

    record Deposit(int wallet, long amount) implements Command {}

    record Transfer(int source, int target, long amount) implements Command {}

    /** How a command ends, without the details. */
    enum Outcome {
        COMPLETED,
        INSUFFICIENT_FUNDS,
        WALLET_NOT_FOUND,
        SAME_WALLET,
        BALANCE_LIMIT_EXCEEDED,
        CURRENCY_MISMATCH
    }

    /** jqwik is not a JUnit Jupiter engine, so the Spring extension does not run: wire the fields by hand. */
    @BeforeProperty
    void injectSpringBeans() throws Exception {
        new TestContextManager(getClass()).prepareTestInstance(this);
    }

    @AfterProperty
    void ledgerIsSound() {
        assertThat(new InvariantChecker(jdbc).violations()).isEmpty();
    }

    @Property(tries = 1000)
    void systemBehavesLikeAnArrayOfBalances(@ForAll("commands") List<Command> commands) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            ids.add(((OpenWalletResult.Opened) wallets.open(VND)).wallet().id());
        }
        ids.add(UUID.randomUUID());
        long[] model = new long[WALLETS];

        for (Command command : commands) {
            Outcome expected = applyToModel(model, command);
            Outcome actual = applyToSystem(ids, command);
            Statistics.collect(command.getClass().getSimpleName(), expected);

            assertThat(actual).as("outcome of %s", command).isEqualTo(expected);
            for (int i = 0; i < WALLETS; i++) {
                assertThat(wallets.find(ids.get(i)).orElseThrow().balance().amount())
                        .as("balance of wallet %d after %s", i, command)
                        .isEqualTo(model[i]);
            }
        }
    }

    /**
     * Mostly commands that succeed, so balances build up and later transfers have something to move. The rest are the
     * rejections: an unknown wallet, a wallet paying itself, more than the balance.
     */
    @Provide
    Arbitrary<List<Command>> commands() {
        Arbitrary<Integer> wallet =
                Arbitraries.frequency(Tuple.of(3, 0), Tuple.of(3, 1), Tuple.of(3, 2), Tuple.of(1, UNKNOWN));
        Arbitrary<Command> deposit = Combinators.combine(
                        wallet, Arbitraries.longs().between(1, 1_000))
                .as(Deposit::new);
        Arbitrary<Command> transfer = Combinators.combine(
                        wallet, wallet, Arbitraries.longs().between(1, 400))
                .as(Transfer::new);
        return Arbitraries.frequencyOf(Tuple.of(2, deposit), Tuple.of(3, transfer))
                .list()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    private static Outcome applyToModel(long[] balances, Command command) {
        switch (command) {
            case Deposit(int wallet, long amount) -> {
                if (wallet == UNKNOWN) {
                    return Outcome.WALLET_NOT_FOUND;
                }
                balances[wallet] += amount;
                return Outcome.COMPLETED;
            }
            case Transfer(int source, int target, long amount) -> {
                if (source == target) {
                    return Outcome.SAME_WALLET;
                }
                if (source == UNKNOWN || target == UNKNOWN) {
                    return Outcome.WALLET_NOT_FOUND;
                }
                if (balances[source] < amount) {
                    return Outcome.INSUFFICIENT_FUNDS;
                }
                balances[source] -= amount;
                balances[target] += amount;
                return Outcome.COMPLETED;
            }
        }
    }

    private Outcome applyToSystem(List<UUID> ids, Command command) {
        return switch (command) {
            case Deposit(int wallet, long amount) ->
                switch (transfers.deposit(ids.get(wallet), Money.of(amount, VND))) {
                    case DepositResult.Completed completed -> Outcome.COMPLETED;
                    case DepositResult.WalletNotFound notFound -> Outcome.WALLET_NOT_FOUND;
                    case DepositResult.BalanceLimitExceeded exceeded -> Outcome.BALANCE_LIMIT_EXCEEDED;
                    case DepositResult.CurrencyMismatch mismatch -> Outcome.CURRENCY_MISMATCH;
                };
            case Transfer(int source, int target, long amount) ->
                switch (transfers.transfer(ids.get(source), ids.get(target), Money.of(amount, VND))) {
                    case TransferResult.Completed completed -> Outcome.COMPLETED;
                    case TransferResult.InsufficientFunds funds -> Outcome.INSUFFICIENT_FUNDS;
                    case TransferResult.WalletNotFound notFound -> Outcome.WALLET_NOT_FOUND;
                    case TransferResult.SameWallet same -> Outcome.SAME_WALLET;
                    case TransferResult.BalanceLimitExceeded exceeded -> Outcome.BALANCE_LIMIT_EXCEEDED;
                    case TransferResult.CurrencyMismatch mismatch -> Outcome.CURRENCY_MISMATCH;
                };
        };
    }
}
