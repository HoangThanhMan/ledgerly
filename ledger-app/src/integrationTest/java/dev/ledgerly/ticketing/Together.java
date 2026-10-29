package dev.ledgerly.ticketing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tasks on virtual threads that wait at a latch and are released together, so they really overlap instead of
 * running one after the other as they are submitted.
 */
final class Together {

    private Together() {}

    /**
     * @return the results in the order of the tasks
     * @throws ExecutionException if a task threw: its exception is the cause
     */
    static <T> List<T> run(List<Callable<T>> tasks, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CountDownLatch start = new CountDownLatch(1);
        List<T> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            for (Future<T> future : futures) {
                results.add(future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS));
            }
        }
        return results;
    }
}
