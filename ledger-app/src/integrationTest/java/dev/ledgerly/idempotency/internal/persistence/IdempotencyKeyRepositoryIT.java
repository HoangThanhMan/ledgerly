package dev.ledgerly.idempotency.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.idempotency.StoredResponse;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository.KeyRow;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository.KeyStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL statements behind an idempotency key, one at a time. Each call here runs in its own transaction, so every
 * test sees exactly what the statement before it committed.
 */
class IdempotencyKeyRepositoryIT extends AbstractIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration ALREADY_OVER = Duration.ofSeconds(-1);

    private final IdempotencyKeyRepository keys;
    private final JdbcClient jdbc;

    IdempotencyKeyRepositoryIT(@Autowired IdempotencyKeyRepository keys, @Autowired JdbcClient jdbc) {
        this.keys = keys;
        this.jdbc = jdbc;
    }

    @Test
    void insertClaimsANewKey() {
        String key = newKey();
        UUID lease = UUID.randomUUID();

        assertThat(keys.insert(key, HASH, lease, LEASE, TTL)).isTrue();

        assertThat(keys.find(key)).contains(new KeyRow(key, HASH, KeyStatus.IN_PROGRESS, lease, null));
    }

    @Test
    void insertLeavesAnExistingKeyUntouched() {
        String key = newKey();
        UUID first = UUID.randomUUID();
        keys.insert(key, HASH, first, LEASE, TTL);

        assertThat(keys.insert(key, "b".repeat(64), UUID.randomUUID(), LEASE, TTL))
                .isFalse();

        assertThat(keys.find(key)).contains(new KeyRow(key, HASH, KeyStatus.IN_PROGRESS, first, null));
    }

    @Test
    void unknownKeyIsNotFound() {
        assertThat(keys.find(newKey())).isEmpty();
    }

    @Test
    void completeStoresTheResponseOfTheLeaseHolder() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);
        StoredResponse response = new StoredResponse(201, "{\"id\":\"t-1\"}", UUID.randomUUID());

        assertThat(keys.complete(key, lease, response)).isTrue();

        assertThat(keys.find(key)).contains(new KeyRow(key, HASH, KeyStatus.COMPLETED, lease, response));
    }

    @Test
    void completeWithAnotherLeaseTokenChangesNothing() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);

        assertThat(keys.complete(key, UUID.randomUUID(), new StoredResponse(201, "{}", null)))
                .isFalse();

        assertThat(keys.find(key)).contains(new KeyRow(key, HASH, KeyStatus.IN_PROGRESS, lease, null));
    }

    @Test
    void completedKeyKeepsItsFirstResponse() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);
        StoredResponse first = new StoredResponse(201, "{\"n\":1}", null);
        keys.complete(key, lease, first);

        assertThat(keys.complete(key, lease, new StoredResponse(201, "{\"n\":2}", null)))
                .isFalse();

        assertThat(keys.find(key).orElseThrow().response()).isEqualTo(first);
    }

    /** A replay must return the bytes of the first response, so the body is not normalized the way JSONB would. */
    @Test
    void responseBodyIsStoredVerbatim() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);
        String body = "{\"status\":\"COMPLETED\",  \"id\":\"t-1\",\"amount\":\"150000\"}";

        keys.complete(key, lease, new StoredResponse(201, body, null));

        assertThat(keys.find(key).orElseThrow().response())
                .extracting(StoredResponse::body)
                .isEqualTo(body);
    }

    @Test
    void responseBodyMustBeJson() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);

        assertThatThrownBy(() -> keys.complete(key, lease, new StoredResponse(201, "not json", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reclaimLeavesALeaseThatIsStillRunning() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);

        assertThat(keys.reclaim(key, UUID.randomUUID(), LEASE)).isFalse();

        assertThat(keys.find(key).orElseThrow().leaseToken()).isEqualTo(lease);
    }

    @Test
    void reclaimTakesOverAnExpiredLeaseAndFencesOffThePreviousHolder() {
        String key = newKey();
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        keys.insert(key, HASH, stale, ALREADY_OVER, TTL);

        assertThat(keys.reclaim(key, fresh, LEASE)).isTrue();

        assertThat(keys.find(key).orElseThrow().leaseToken()).isEqualTo(fresh);
        // The new lease runs again, so a third request cannot take the key from the second.
        assertThat(keys.reclaim(key, UUID.randomUUID(), LEASE)).isFalse();
        StoredResponse response = new StoredResponse(201, "{}", null);
        assertThat(keys.complete(key, stale, response)).isFalse();
        assertThat(keys.complete(key, fresh, response)).isTrue();
    }

    @Test
    void reclaimNeverTakesACompletedKey() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, ALREADY_OVER, TTL);
        keys.complete(key, lease, new StoredResponse(201, "{}", null));

        assertThat(keys.reclaim(key, UUID.randomUUID(), LEASE)).isFalse();

        assertThat(keys.find(key).orElseThrow().status()).isEqualTo(KeyStatus.COMPLETED);
    }

    @Test
    void releaseEndsTheLeaseSoTheKeyCanBeReclaimedAtOnce() {
        String key = newKey();
        UUID lease = UUID.randomUUID();
        keys.insert(key, HASH, lease, LEASE, TTL);

        keys.release(key, lease);

        assertThat(keys.reclaim(key, UUID.randomUUID(), LEASE)).isTrue();
    }

    @Test
    void releaseWithAnotherLeaseTokenChangesNothing() {
        String key = newKey();
        keys.insert(key, HASH, UUID.randomUUID(), LEASE, TTL);

        keys.release(key, UUID.randomUUID());

        assertThat(keys.reclaim(key, UUID.randomUUID(), LEASE)).isFalse();
    }

    @Test
    void deleteExpiredRemovesExpiredKeysOfAnyStatusUpToTheLimit() {
        while (keys.deleteExpired(1_000) > 0) {
            // Start from a table without expired keys, whatever earlier tests left behind.
        }
        String live = newKey();
        keys.insert(live, HASH, UUID.randomUUID(), LEASE, TTL);
        for (int i = 0; i < 3; i++) {
            UUID lease = UUID.randomUUID();
            String expired = newKey();
            keys.insert(expired, HASH, lease, LEASE, Duration.ofHours(-1));
            if (i == 0) {
                keys.complete(expired, lease, new StoredResponse(201, "{}", null));
            }
        }

        assertThat(keys.deleteExpired(2)).isEqualTo(2);
        assertThat(keys.deleteExpired(2)).isEqualTo(1);
        assertThat(keys.deleteExpired(2)).isZero();

        assertThat(keys.find(live)).isPresent();
    }

    @Test
    void schemaRejectsACompletedKeyWithoutAResponse() {
        String key = newKey();
        keys.insert(key, HASH, UUID.randomUUID(), LEASE, TTL);

        assertThatThrownBy(() -> jdbc.sql("UPDATE idempotency_keys SET status = 'COMPLETED' WHERE idem_key = :key")
                        .param("key", key)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("completed_has_response");
    }

    @Test
    void schemaRejectsAnUnknownStatus() {
        String key = newKey();
        keys.insert(key, HASH, UUID.randomUUID(), LEASE, TTL);

        assertThatThrownBy(() -> jdbc.sql("UPDATE idempotency_keys SET status = 'FAILED' WHERE idem_key = :key")
                        .param("key", key)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String newKey() {
        return "repo-" + UUID.randomUUID();
    }
}
