package dev.ledgerly.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.idempotency.IdempotencyResult.Executed;
import dev.ledgerly.idempotency.IdempotencyResult.InProgress;
import dev.ledgerly.idempotency.IdempotencyResult.KeyReused;
import dev.ledgerly.idempotency.IdempotencyResult.Replayed;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository.KeyStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The branches of {@link IdempotencyApi#execute}, with actions that stand in for a real endpoint. */
class IdempotencyApiIT extends AbstractIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration ALREADY_OVER = Duration.ofSeconds(-1);
    private static final String REPLAYS_COUNTER = "ledgerly.idempotency.replays";

    private final IdempotencyApi idempotency;
    private final IdempotencyKeyRepository keys;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    IdempotencyApiIT(
            @Autowired IdempotencyApi idempotency,
            @Autowired IdempotencyKeyRepository keys,
            @Autowired JdbcClient jdbc,
            @Autowired MeterRegistry meters) {
        this.idempotency = idempotency;
        this.keys = keys;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Test
    void firstCallRunsTheActionAndStoresItsResponse() {
        String key = newKey();
        StoredResponse response = created();

        assertThat(idempotency.execute(key, HASH, () -> response)).isEqualTo(new Executed(response));

        assertThat(keys.find(key)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(KeyStatus.COMPLETED);
            assertThat(row.response()).isEqualTo(response);
        });
    }

    @Test
    void laterCallsReplayTheStoredResponseWithoutRunningTheAction() {
        String key = newKey();
        StoredResponse response = created();
        AtomicInteger runs = new AtomicInteger();
        Supplier<StoredResponse> action = () -> {
            runs.incrementAndGet();
            return response;
        };
        idempotency.execute(key, HASH, action);
        double replaysBefore = replays();

        assertThat(idempotency.execute(key, HASH, action)).isEqualTo(new Replayed(response));
        assertThat(idempotency.execute(key, HASH, action)).isEqualTo(new Replayed(response));

        assertThat(runs).hasValue(1);
        assertThat(replays() - replaysBefore).isEqualTo(2);
    }

    @Test
    void firstCallIsNotCountedAsAReplay() {
        double replaysBefore = replays();

        idempotency.execute(newKey(), HASH, IdempotencyApiIT::created);

        assertThat(replays()).isEqualTo(replaysBefore);
    }

    @Test
    void keyUsedWithAnotherRequestIsReportedAndTheActionDoesNotRun() {
        String key = newKey();
        StoredResponse first = created();
        idempotency.execute(key, HASH, () -> first);

        assertThat(idempotency.execute(key, OTHER_HASH, IdempotencyApiIT::failIfRun))
                .isEqualTo(new KeyReused());

        assertThat(keys.find(key).orElseThrow().response()).isEqualTo(first);
    }

    @Test
    void keyUsedWithAnotherRequestIsReportedEvenWhileTheFirstIsStillRunning() {
        String key = newKey();
        keys.insert(key, HASH, UUID.randomUUID(), LEASE, TTL);

        assertThat(idempotency.execute(key, OTHER_HASH, IdempotencyApiIT::failIfRun))
                .isEqualTo(new KeyReused());
    }

    @Test
    void keyHeldByARunningRequestIsInProgress() {
        String key = newKey();
        UUID holder = UUID.randomUUID();
        keys.insert(key, HASH, holder, LEASE, TTL);

        assertThat(idempotency.execute(key, HASH, IdempotencyApiIT::failIfRun))
                .isEqualTo(new InProgress(Duration.ofSeconds(1)));

        assertThat(keys.find(key).orElseThrow().leaseToken()).isEqualTo(holder);
    }

    @Test
    void keyWhoseHolderLetTheLeaseRunOutIsTakenOver() {
        String key = newKey();
        UUID crashed = UUID.randomUUID();
        keys.insert(key, HASH, crashed, ALREADY_OVER, TTL);
        StoredResponse response = created();

        assertThat(idempotency.execute(key, HASH, () -> response)).isEqualTo(new Executed(response));

        assertThat(keys.find(key)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(KeyStatus.COMPLETED);
            assertThat(row.leaseToken()).isNotEqualTo(crashed);
        });
    }

    @Test
    void actionAndCompletionAreOneTransaction() {
        String key = newKey();
        String writtenByAction = newKey();

        idempotency.execute(key, HASH, () -> {
            keys.insert(writtenByAction, HASH, UUID.randomUUID(), LEASE, TTL);
            // Another connection sees neither the action's write nor the completed key before the commit.
            assertThat(onAnotherConnection(() -> keys.find(writtenByAction))).isEmpty();
            assertThat(onAnotherConnection(() -> keys.find(key)).orElseThrow().status())
                    .isEqualTo(KeyStatus.IN_PROGRESS);
            return created();
        });

        assertThat(keys.find(writtenByAction)).isPresent();
    }

    @Test
    void failedActionIsRolledBackAndTheKeyIsReleasedForARetry() {
        String key = newKey();
        String writtenByAction = newKey();
        IllegalStateException failure = new IllegalStateException("database connection lost");

        assertThatThrownBy(() -> idempotency.execute(key, HASH, () -> {
                    keys.insert(writtenByAction, HASH, UUID.randomUUID(), LEASE, TTL);
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(keys.find(writtenByAction)).isEmpty();
        assertThat(keys.find(key).orElseThrow().status()).isEqualTo(KeyStatus.IN_PROGRESS);
        // No waiting for the 30 second lease: the retry takes the key at once.
        StoredResponse response = created();
        assertThat(idempotency.execute(key, HASH, () -> response)).isEqualTo(new Executed(response));
    }

    @Test
    void requestThatLostItsLeaseIsRolledBackAndDoesNotOverwriteTheNewHolder() {
        String key = newKey();
        String writtenByAction = newKey();
        UUID newHolder = UUID.randomUUID();

        IdempotencyResult result = idempotency.execute(key, HASH, () -> {
            keys.insert(writtenByAction, HASH, UUID.randomUUID(), LEASE, TTL);
            // While this request is busy, its lease runs out and another request takes the key over.
            onAnotherConnection(() -> jdbc.sql("UPDATE idempotency_keys SET lease_token = :token WHERE idem_key = :key")
                    .param("token", newHolder)
                    .param("key", key)
                    .update());
            return created();
        });

        assertThat(result).isEqualTo(new InProgress(Duration.ofSeconds(1)));
        assertThat(keys.find(writtenByAction)).isEmpty();
        assertThat(keys.find(key)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(KeyStatus.IN_PROGRESS);
            assertThat(row.leaseToken()).isEqualTo(newHolder);
        });
    }

    private double replays() {
        return meters.counter(REPLAYS_COUNTER).count();
    }

    /** Runs on another thread, so outside the caller's transaction and on a connection of its own. */
    private static <T> T onAnotherConnection(Supplier<T> query) {
        return CompletableFuture.supplyAsync(query).join();
    }

    private static StoredResponse created() {
        UUID id = UUID.randomUUID();
        return new StoredResponse(201, "{\"id\":\"" + id + "\"}", id);
    }

    private static StoredResponse failIfRun() {
        throw new AssertionError("the action must not run");
    }

    private static String newKey() {
        return "api-" + UUID.randomUUID();
    }
}
