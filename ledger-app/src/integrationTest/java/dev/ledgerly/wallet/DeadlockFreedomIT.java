package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.wallet.ConcurrentTransfers.Outcome;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferResult.Completed;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two wallets pay each other at the same time, over and over. A→B and B→A touch the same two rows in opposite order,
 * which deadlocks unless every transfer takes its row locks in one global order.
 */
class DeadlockFreedomIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DeadlockFreedomIT.class);

    private static final int PAIRS = Integer.getInteger("ledgerly.test.deadlockPairs", 1_000);
    private static final long INITIAL_BALANCE = 1_000_000;

    private final ConcurrentTransfers support;
    private final InvariantChecker invariants;

    DeadlockFreedomIT(
            @Autowired WalletService wallets, @Autowired TransferService transfers, @Autowired JdbcClient jdbc) {
        this.support = new ConcurrentTransfers(wallets, transfers);
        this.invariants = new InvariantChecker(jdbc);
    }

    @Test
    void oppositeTransfersBetweenTwoWalletsNeverDeadlock() throws Exception {
        UUID a = support.openWallet(INITIAL_BALANCE);
        UUID b = support.openWallet(INITIAL_BALANCE);

        List<Callable<List<TransferResult>>> tasks = new ArrayList<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            tasks.add(() -> List.of(support.transfer(a, b, 1)));
            tasks.add(() -> List.of(support.transfer(b, a, 1)));
        }
        Outcome outcome = ConcurrentTransfers.runTogether(tasks, 300);

        log.info(
                "DeadlockFreedomIT: {} pairs, {} completed, {} failed {}",
                PAIRS,
                outcome.results().size(),
                outcome.failures().size(),
                outcome.failureCauses());
        assertThat(outcome.deadlocks())
                .as("transfers aborted by a deadlock (40P01)")
                .isZero();
        assertThat(outcome.failures())
                .as("transfers that ended in an exception")
                .isEmpty();
        assertThat(outcome.results())
                .hasSize(2 * PAIRS)
                .allSatisfy(result -> assertThat(result).isInstanceOf(Completed.class));
        assertThat(support.balanceOf(a)).isEqualTo(INITIAL_BALANCE);
        assertThat(support.balanceOf(b)).isEqualTo(INITIAL_BALANCE);
        assertThat(invariants.violations()).isEmpty();
    }
}
