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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Random transfers between a few wallets from many threads at once. Money must neither appear nor vanish: every
 * balance must equal what the completed transfers add up to.
 *
 * <p>The number of transfers and the random seed can be set with the system properties
 * {@code ledgerly.test.concurrentTransfers} and {@code ledgerly.test.seed}. The seed is logged to replay a failure.
 */
class ConcurrentTransferIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransferIT.class);

    private static final int WALLETS = 10;
    private static final int THREADS = 200;
    private static final int TRANSFERS = Integer.getInteger("ledgerly.test.concurrentTransfers", 10_000);
    private static final long INITIAL_BALANCE = 10_000;
    // Up to half a wallet's initial balance, so wallets do run dry and rejections happen under contention too.
    private static final int MAX_AMOUNT = 5_000;

    private final ConcurrentTransfers support;
    private final InvariantChecker invariants;

    ConcurrentTransferIT(
            @Autowired WalletService wallets, @Autowired TransferService transfers, @Autowired JdbcClient jdbc) {
        this.support = new ConcurrentTransfers(wallets, transfers);
        this.invariants = new InvariantChecker(jdbc);
    }

    @Test
    void concurrentTransfersPreserveInvariants() throws Exception {
        long seed = Long.getLong("ledgerly.test.seed", System.nanoTime());
        log.info("ConcurrentTransferIT: {} transfers on {} threads, seed {}", TRANSFERS, THREADS, seed);
        List<UUID> wallets = IntStream.range(0, WALLETS)
                .mapToObj(i -> support.openWallet(INITIAL_BALANCE))
                .toList();

        List<Callable<List<TransferResult>>> tasks = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            Random random = new Random(seed + thread);
            int transfers = TRANSFERS / THREADS;
            tasks.add(() -> randomTransfers(wallets, random, transfers));
        }
        Outcome outcome = ConcurrentTransfers.runTogether(tasks, 300);

        assertThat(outcome.failures())
                .as("transfers that ended in an exception")
                .isEmpty();
        assertThat(outcome.results())
                .hasSize(TRANSFERS)
                .allSatisfy(result -> assertThat(result).isInstanceOfAny(Completed.class, InsufficientFunds.class));

        Map<UUID, Long> expected = new HashMap<>();
        wallets.forEach(wallet -> expected.put(wallet, INITIAL_BALANCE));
        long completed = 0;
        for (TransferResult result : outcome.results()) {
            if (result instanceof Completed(var transfer)) {
                completed++;
                expected.merge(transfer.sourceWalletId(), -transfer.amount().amount(), Long::sum);
                expected.merge(transfer.targetWalletId(), transfer.amount().amount(), Long::sum);
            }
        }
        log.info(
                "ConcurrentTransferIT: {} completed, {} rejected for insufficient funds",
                completed,
                TRANSFERS - completed);

        Map<UUID, Long> actual = new HashMap<>();
        wallets.forEach(wallet -> actual.put(wallet, support.balanceOf(wallet)));
        assertThat(actual).as("balances after the completed transfers").isEqualTo(expected);
        assertThat(actual.values()).allSatisfy(balance -> assertThat(balance).isNotNegative());
        assertThat(actual.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(WALLETS * INITIAL_BALANCE);
        assertThat(invariants.violations()).isEmpty();
    }

    private List<TransferResult> randomTransfers(List<UUID> wallets, Random random, int count) {
        List<TransferResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int source = random.nextInt(wallets.size());
            int target = (source + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            results.add(support.transfer(wallets.get(source), wallets.get(target), 1 + random.nextInt(MAX_AMOUNT)));
        }
        return results;
    }
}
