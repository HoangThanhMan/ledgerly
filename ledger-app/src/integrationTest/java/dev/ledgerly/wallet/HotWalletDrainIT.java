package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.wallet.ConcurrentTransfers.Outcome;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferResult.Completed;
import dev.ledgerly.wallet.internal.application.TransferResult.InsufficientFunds;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Many threads spend from one wallet that can afford only some of them. Exactly as many transfers succeed as the
 * balance allows: one more would be a double spend.
 */
class HotWalletDrainIT extends AbstractIntegrationTest {

    private static final int THREADS = 500;
    private static final long BALANCE = 100;

    private final ConcurrentTransfers support;
    private final InvariantChecker invariants;

    HotWalletDrainIT(
            @Autowired WalletService wallets, @Autowired TransferService transfers, @Autowired JdbcClient jdbc) {
        this.support = new ConcurrentTransfers(wallets, transfers);
        this.invariants = new InvariantChecker(jdbc);
    }

    @Test
    void walletIsDrainedExactlyOnceUnderContention() throws Exception {
        UUID hot = support.openWallet(BALANCE);
        UUID sink = support.openWallet(0);

        List<Callable<List<TransferResult>>> tasks = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            tasks.add(() -> List.of(support.transfer(hot, sink, 1)));
        }
        Outcome outcome = ConcurrentTransfers.runTogether(tasks, 300);

        assertThat(outcome.failures())
                .as("transfers that ended in an exception")
                .isEmpty();
        assertThat(outcome.results()).filteredOn(Completed.class::isInstance).hasSize((int) BALANCE);
        assertThat(outcome.results())
                .filteredOn(InsufficientFunds.class::isInstance)
                .hasSize(THREADS - (int) BALANCE);
        assertThat(support.balanceOf(hot)).isZero();
        assertThat(support.balanceOf(sink)).isEqualTo(BALANCE);
        assertThat(invariants.violations()).isEmpty();
    }
}
