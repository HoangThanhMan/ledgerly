package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.DepositResult;
import dev.ledgerly.wallet.internal.application.OpenWalletResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Shared plumbing of the concurrency tests: wallets with money, and tasks that all start at the same instant. */
final class ConcurrentTransfers {

    static final Currency VND = Currency.getInstance("VND");

    private static final String DEADLOCK_DETECTED = "40P01";

    private final WalletService wallets;
    private final TransferService transfers;

    ConcurrentTransfers(WalletService wallets, TransferService transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    /** What became of the tasks of one run: the transfer results, and the tasks that ended in an exception. */
    record Outcome(List<TransferResult> results, List<Throwable> failures) {

        long deadlocks() {
            return failures.stream().filter(ConcurrentTransfers::isDeadlock).count();
        }

        /** How many tasks failed for each cause: the SQLSTATE if the database raised it, else the exception type. */
        Map<String, Long> failureCauses() {
            return failures.stream()
                    .collect(Collectors.groupingBy(ConcurrentTransfers::causeOf, TreeMap::new, Collectors.counting()));
        }
    }

    UUID openWallet(long balance) {
        UUID wallet = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        if (balance > 0) {
            assertThat(transfers.deposit(wallet, vnd(balance))).isInstanceOf(DepositResult.Completed.class);
        }
        return wallet;
    }

    long balanceOf(UUID wallet) {
        return wallets.find(wallet).orElseThrow().balance().amount();
    }

    TransferResult transfer(UUID source, UUID target, long amount) {
        return transfers.transfer(source, target, vnd(amount));
    }

    /**
     * Runs every task on its own virtual thread. The threads wait at a latch and are released together, so the tasks
     * really overlap instead of running one after the other as they are submitted.
     */
    static Outcome runTogether(List<Callable<List<TransferResult>>> tasks, long timeoutSeconds)
            throws InterruptedException, TimeoutException {
        CountDownLatch start = new CountDownLatch(1);
        List<TransferResult> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<TransferResult>>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            for (Future<List<TransferResult>> future : futures) {
                try {
                    results.addAll(future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }
        return new Outcome(results, failures);
    }

    static Money vnd(long amount) {
        return Money.of(amount, VND);
    }

    private static boolean isDeadlock(Throwable failure) {
        return ("SQLSTATE " + DEADLOCK_DETECTED).equals(causeOf(failure));
    }

    private static String causeOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return "SQLSTATE " + sql.getSQLState();
            }
        }
        return failure.getClass().getSimpleName();
    }
}
