package dev.ledgerly.idempotency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.idempotency.IdempotencyApi;
import dev.ledgerly.idempotency.IdempotencyResult.Executed;
import dev.ledgerly.idempotency.IdempotencyResult.Replayed;
import dev.ledgerly.idempotency.StoredResponse;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class IdempotencyCleanupIT extends AbstractIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration EXPIRED_AN_HOUR_AGO = Duration.ofHours(-1);

    private final IdempotencyCleanupJob cleanup;
    private final IdempotencyApi idempotency;
    private final IdempotencyKeyRepository keys;
    private final JdbcClient jdbc;
    private final ScheduledTaskHolder scheduledTasks;

    IdempotencyCleanupIT(
            @Autowired IdempotencyCleanupJob cleanup,
            @Autowired IdempotencyApi idempotency,
            @Autowired IdempotencyKeyRepository keys,
            @Autowired JdbcClient jdbc,
            @Autowired ScheduledTaskHolder scheduledTasks) {
        this.cleanup = cleanup;
        this.idempotency = idempotency;
        this.keys = keys;
        this.jdbc = jdbc;
        this.scheduledTasks = scheduledTasks;
    }

    @Test
    void expiredKeysAreDeletedAndTheOthersStay() {
        String expiredCompleted = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(expiredCompleted, HASH, lease, LEASE, EXPIRED_AN_HOUR_AGO);
        keys.complete(expiredCompleted, lease, new StoredResponse(201, "{}", null));
        // Claimed by a request that crashed and was never retried.
        String expiredInProgress = newKey();
        keys.insert(expiredInProgress, HASH, UUID.randomUUID(), LEASE, EXPIRED_AN_HOUR_AGO);
        String live = newKey();
        keys.insert(live, HASH, UUID.randomUUID(), LEASE, Duration.ofHours(24));
        // Not a minute: a key that expired while a later test counts deletions would make that count wrong.
        String expiresInAnHour = newKey();
        keys.insert(expiresInAnHour, HASH, UUID.randomUUID(), LEASE, Duration.ofHours(1));

        assertThat(cleanup.deleteExpired()).isGreaterThanOrEqualTo(2);

        assertThat(keys.find(expiredCompleted)).isEmpty();
        assertThat(keys.find(expiredInProgress)).isEmpty();
        assertThat(keys.find(live)).isPresent();
        assertThat(keys.find(expiresInAnHour)).isPresent();
    }

    @Test
    void expiredKeyStillReplaysUntilItIsDeletedAndIsFreeForANewRequestAfterwards() {
        String key = newKey();
        StoredResponse first = new StoredResponse(201, "{\"n\":1}", null);
        idempotency.execute(key, HASH, () -> first);
        jdbc.sql("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE idem_key = :key")
                .param("key", key)
                .update();

        // Past its time to live, but no cleanup has run yet: the key is kept at least that long, not exactly.
        assertThat(idempotency.execute(key, HASH, () -> new StoredResponse(500, "{}", null)))
                .isEqualTo(new Replayed(first));

        cleanup.deleteExpired();

        // The key is gone, so even a different request may use it now.
        StoredResponse second = new StoredResponse(201, "{\"n\":2}", null);
        assertThat(idempotency.execute(key, "b".repeat(64), () -> second)).isEqualTo(new Executed(second));
    }

    @Test
    void oneRunDeletesInBatchesUntilNoExpiredKeyIsLeft() {
        cleanup.deleteExpired();
        int expired = 2_500;
        jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (idem_key, request_hash, status, lease_token, locked_until, expires_at)
                        SELECT 'cleanup-' || :run || '-' || n, :hash, 'IN_PROGRESS', gen_random_uuid(), now(),
                            now() - interval '1 hour'
                        FROM generate_series(1, :count) AS n
                        """)
                .param("run", UUID.randomUUID().toString())
                .param("hash", HASH)
                .param("count", expired)
                .update();

        // More than two full batches of 1000, so the run has to go around three times.
        assertThat(cleanup.deleteExpired()).isEqualTo(expired);

        assertThat(expiredKeysLeft()).isZero();
    }

    @Test
    void runWithNothingToDeleteDeletesNothing() {
        cleanup.deleteExpired();

        assertThat(cleanup.deleteExpired()).isZero();
    }

    @Test
    void cleanupIsScheduledWithAFixedDelay() {
        assertThat(scheduledTasks.getScheduledTasks())
                .filteredOn(task -> task.toString().contains("IdempotencyCleanupJob.deleteExpiredKeys"))
                .singleElement()
                .satisfies(task -> assertThat(task.getTask())
                        .isInstanceOfSatisfying(FixedDelayTask.class, fixedDelay -> {
                            assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
                            assertThat(fixedDelay.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(10));
                        }));
    }

    private long expiredKeysLeft() {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE expires_at < now()")
                .query(Long.class)
                .single();
    }

    private static String newKey() {
        return "cleanup-" + UUID.randomUUID();
    }
}
