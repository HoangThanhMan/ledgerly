package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.wallet.RetryingClient.Answer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Invariant I5: one key produces at most one transaction. Fifty clients send the same request under the same key at
 * the same instant, the way retries pile up when a client times out too early. Exactly one of them moves the money,
 * and all of them end up with the same answer.
 */
class IdempotencyConcurrencyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyIT.class);

    private static final int CLIENTS = 50;

    private final WalletApiDriver api;
    private final RetryingClient clients;
    private final InvariantChecker invariants;
    private final MeterRegistry meters;

    IdempotencyConcurrencyIT(
            @Autowired RestTestClient client, @Autowired JdbcClient jdbc, @Autowired MeterRegistry meters) {
        this.api = new WalletApiDriver(client);
        this.clients = new RetryingClient(api, jdbc);
        this.invariants = new InvariantChecker(jdbc);
        this.meters = meters;
    }

    @Test
    void fiftyRequestsWithOneKeyMoveTheMoneyOnceAndAllGetTheSameAnswer() throws Exception {
        UUID source = api.openWalletWith(1_000);
        UUID target = api.openWallet();
        double replaysBefore = replays();

        List<Answer> answers = sendTogether(UUID.randomUUID().toString(), source, target, "300");

        assertThat(answers).hasSize(CLIENTS);
        assertThat(answers).extracting(Answer::status).containsOnly(201);
        assertThat(answers)
                .extracting(Answer::body)
                .containsOnly(answers.getFirst().body());
        assertThat(answers).filteredOn(Answer::replayed).hasSize(CLIENTS - 1);
        assertThat(clients.transfersInto(target)).isEqualTo(1);
        assertThat(api.balanceOf(source)).isEqualTo("700");
        assertThat(api.balanceOf(target)).isEqualTo("300");
        assertThat(replays() - replaysBefore).isEqualTo(CLIENTS - 1);
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void fiftyRequestsForATransferTheWalletCannotAffordAllGetTheSameRejection() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        List<Answer> answers = sendTogether(UUID.randomUUID().toString(), source, target, "101");

        assertThat(answers).hasSize(CLIENTS);
        assertThat(answers).extracting(Answer::status).containsOnly(422);
        assertThat(answers)
                .extracting(Answer::body)
                .containsOnly(answers.getFirst().body());
        assertThat(answers.getFirst().body()).contains("/problems/insufficient-funds");
        assertThat(answers).filteredOn(Answer::replayed).hasSize(CLIENTS - 1);
        assertThat(clients.transfersInto(target)).isZero();
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    /**
     * Every client runs on its own virtual thread. The threads wait at a latch and are released together, so the
     * requests really race for the key.
     */
    private List<Answer> sendTogether(String key, UUID source, UUID target, String amount) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Answer> answers = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Answer>> futures = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return clients.transfer(key, source, target, amount);
                }));
            }
            start.countDown();
            for (Future<Answer> future : futures) {
                answers.add(future.get(60, TimeUnit.SECONDS));
            }
        }
        long conflicted =
                answers.stream().filter(answer -> answer.conflicts() > 0).count();
        log.info("{} of {} clients were told to retry (409) at least once", conflicted, CLIENTS);
        return answers;
    }

    private double replays() {
        return meters.counter("ledgerly.idempotency.replays").count();
    }
}
