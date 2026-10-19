package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.IDEMPOTENT_REPLAYED;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static dev.ledgerly.wallet.WalletApiDriver.rawBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.idempotency.internal.application.FaultInjector;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.wallet.RetryingClient.Answer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Requests that break in the middle. A key's lease lasts one second here instead of thirty, so the tests can wait
 * for it to run out.
 */
@TestPropertySource(properties = "ledgerly.idempotency.lease=1s")
class IdempotencyCrashRecoveryIT extends AbstractIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** Breaks the request of a given key once, at the point a test asks for. Every other request is left alone. */
    static final class ScriptedFaults implements FaultInjector {

        /** Thrown where the process is supposed to have died. */
        static final class SimulatedCrash extends RuntimeException {

            private static final long serialVersionUID = 1L;

            SimulatedCrash(String message) {
                super(message);
            }
        }

        /** A request held still inside its transaction until the test lets it go on. */
        record Stall(CountDownLatch reached, CountDownLatch resume) {}

        private final Set<String> crashAfterClaim = ConcurrentHashMap.newKeySet();
        private final Set<String> failBeforeComplete = ConcurrentHashMap.newKeySet();
        private final Map<String, Stall> stallBeforeComplete = new ConcurrentHashMap<>();

        void crashOnceAfterClaim(String key) {
            crashAfterClaim.add(key);
        }

        void failOnceBeforeComplete(String key) {
            failBeforeComplete.add(key);
        }

        Stall stallOnceBeforeComplete(String key) {
            Stall stall = new Stall(new CountDownLatch(1), new CountDownLatch(1));
            stallBeforeComplete.put(key, stall);
            return stall;
        }

        @Override
        public void afterClaim(String key) {
            if (crashAfterClaim.remove(key)) {
                throw new SimulatedCrash("crashed after claiming key " + key);
            }
        }

        @Override
        public void beforeComplete(String key) {
            if (failBeforeComplete.remove(key)) {
                throw new SimulatedCrash("failed before completing key " + key);
            }
            Stall stall = stallBeforeComplete.remove(key);
            if (stall != null) {
                stall.reached().countDown();
                try {
                    if (!stall.resume().await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never resumed the stalled request");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {

        @Bean
        ScriptedFaults scriptedFaults() {
            return new ScriptedFaults();
        }
    }

    private final ScriptedFaults faults;
    private final JdbcClient jdbc;
    private final WalletApiDriver api;
    private final RetryingClient client;
    private final InvariantChecker invariants;

    IdempotencyCrashRecoveryIT(
            @Autowired RestTestClient restClient, @Autowired ScriptedFaults faults, @Autowired JdbcClient jdbc) {
        this.faults = faults;
        this.jdbc = jdbc;
        this.api = new WalletApiDriver(restClient);
        this.client = new RetryingClient(api, jdbc);
        this.invariants = new InvariantChecker(jdbc);
    }

    @Test
    void keyLeftInProgressByACrashIsFinishedByARetryOnceTheLeaseHasRunOut() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = UUID.randomUUID().toString();
        faults.crashOnceAfterClaim(key);

        expectProblem(api.transfer(key, source, target, "10"), HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");

        // The claim was committed, the transfer never started, and nobody released the key.
        assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");
        assertThat(client.transfersInto(target)).isZero();

        Answer answer = client.transfer(key, source, target, "10");

        assertThat(answer.status()).isEqualTo(201);
        assertThat(answer.replayed()).isFalse();
        assertThat(statusOf(key)).isEqualTo("COMPLETED");
        assertThat(client.transfersInto(target)).isEqualTo(1);
        assertThat(api.balanceOf(source)).isEqualTo("90");
        assertThat(api.balanceOf(target)).isEqualTo("10");
        Answer again = client.transfer(key, source, target, "10");
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(answer.body());
        assertThat(client.transfersInto(target)).isEqualTo(1);
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void requestThatOutlivesItsLeaseIsRolledBackOnceARetryHasTakenTheKeyOver() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = UUID.randomUUID().toString();
        ScriptedFaults.Stall stall = faults.stallOnceBeforeComplete(key);

        // The zombie: it has written the transfer inside its open transaction, and then stops responding.
        CompletableFuture<RestTestClient.ResponseSpec> zombie =
                CompletableFuture.supplyAsync(() -> api.transfer(key, source, target, "10"));
        assertThat(stall.reached().await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        UUID zombieLease = leaseOf(key);
        await().atMost(PATIENCE).until(() -> leaseHasRunOut(key));

        // The retry takes the key over, then waits for the wallet locks the zombie's transaction still holds.
        CompletableFuture<RestTestClient.ResponseSpec> retry =
                CompletableFuture.supplyAsync(() -> api.transfer(key, source, target, "10"));
        await().atMost(PATIENCE).until(() -> !leaseOf(key).equals(zombieLease));
        stall.resume().countDown();

        RestTestClient.ResponseSpec zombieResponse = zombie.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        expectProblem(zombieResponse, HttpStatus.CONFLICT, "idempotency-in-progress");
        zombieResponse.expectHeader().valueEquals("Retry-After", "1");
        RestTestClient.ResponseSpec retryResponse = retry.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        retryResponse.expectStatus().isCreated().expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);

        // The zombie's lease token matches nothing any more: its transfer was rolled back, the retry's is the one.
        assertThat(keysHeldUnder(zombieLease)).isZero();
        assertThat(statusOf(key)).isEqualTo("COMPLETED");
        assertThat(client.transfersInto(target)).isEqualTo(1);
        assertThat(api.balanceOf(source)).isEqualTo("90");
        assertThat(api.balanceOf(target)).isEqualTo("10");
        RestTestClient.ResponseSpec replay = api.transfer(key, source, target, "10");
        replay.expectStatus().isCreated().expectHeader().valueEquals(IDEMPOTENT_REPLAYED, "true");
        assertThat(rawBody(replay)).isEqualTo(rawBody(retryResponse));
        assertThat(invariants.violations()).isEmpty();
    }

    @Test
    void failureAfterTheMoneyMovedButBeforeTheKeyIsCompletedUndoesTheTransfer() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = UUID.randomUUID().toString();
        faults.failOnceBeforeComplete(key);

        expectProblem(api.transfer(key, source, target, "10"), HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");

        // Transfer and stored response are one transaction: without the response there is no transfer either.
        assertThat(client.transfersInto(target)).isZero();
        assertThat(api.balanceOf(source)).isEqualTo("100");
        assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");

        // The failed request released the key, so the retry does not have to wait for the lease.
        api.transfer(key, source, target, "10")
                .expectStatus()
                .isCreated()
                .expectHeader()
                .doesNotExist(IDEMPOTENT_REPLAYED);
        assertThat(client.transfersInto(target)).isEqualTo(1);
        assertThat(api.balanceOf(target)).isEqualTo("10");
        assertThat(invariants.violations()).isEmpty();
    }

    private String statusOf(String key) {
        return jdbc.sql("SELECT status FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(String.class)
                .single();
    }

    private UUID leaseOf(String key) {
        return jdbc.sql("SELECT lease_token FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(UUID.class)
                .single();
    }

    private boolean leaseHasRunOut(String key) {
        return jdbc.sql("SELECT locked_until <= now() FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(Boolean.class)
                .single();
    }

    private long keysHeldUnder(UUID leaseToken) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE lease_token = :token")
                .param("token", leaseToken)
                .query(Long.class)
                .single();
    }
}
