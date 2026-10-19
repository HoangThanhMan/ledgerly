package dev.ledgerly.idempotency.internal.persistence;

import dev.ledgerly.idempotency.StoredResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every state change of an idempotency key is a single statement whose {@code WHERE} clause states what must still
 * be true, so two requests racing for the same key are decided by the database and not by what either of them read
 * earlier. Times are compared with the database clock ({@code now()}): application instances need not agree on the
 * time.
 */
@Repository
public class IdempotencyKeyRepository {

    public enum KeyStatus {
        IN_PROGRESS,
        COMPLETED
    }

    /** A row of {@code idempotency_keys}. The response is null until the key is {@code COMPLETED}. */
    public record KeyRow(
            String key,
            String requestHash,
            KeyStatus status,
            UUID leaseToken,
            @Nullable StoredResponse response) {}

    private final JdbcClient jdbc;

    IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims a key nobody has used yet: it becomes {@code IN_PROGRESS}, held under {@code leaseToken} for
     * {@code lease}.
     *
     * @return false if the key already exists, in which case nothing changes
     */
    public boolean insert(String key, String requestHash, UUID leaseToken, Duration lease, Duration ttl) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (idem_key, request_hash, status, lease_token, locked_until, expires_at)
                        VALUES (:key, :hash, 'IN_PROGRESS', :token,
                            now() + :leaseMillis * interval '1 millisecond',
                            now() + :ttlMillis * interval '1 millisecond')
                        ON CONFLICT (idem_key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .param("token", leaseToken)
                .param("leaseMillis", lease.toMillis())
                .param("ttlMillis", ttl.toMillis())
                .update();
        return inserted == 1;
    }

    public Optional<KeyRow> find(String key) {
        return jdbc.sql("""
                        SELECT idem_key, request_hash, status, lease_token, response_status, response_body, resource_id
                        FROM idempotency_keys
                        WHERE idem_key = :key
                        """)
                .param("key", key)
                .query(IdempotencyKeyRepository::toRow)
                .optional();
    }

    /**
     * Takes over a key whose holder let its lease run out, because it crashed or is too slow. The new token fences
     * the previous holder off: its {@link #complete} no longer matches.
     *
     * @return false if the lease is still running or the key is completed, in which case nothing changes
     */
    public boolean reclaim(String key, UUID newLeaseToken, Duration lease) {
        int reclaimed = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET lease_token = :token, locked_until = now() + :leaseMillis * interval '1 millisecond'
                        WHERE idem_key = :key AND status = 'IN_PROGRESS' AND locked_until <= now()
                        """)
                .param("token", newLeaseToken)
                .param("leaseMillis", lease.toMillis())
                .param("key", key)
                .update();
        return reclaimed == 1;
    }

    /**
     * Stores the response and marks the key {@code COMPLETED}, if {@code leaseToken} still holds it.
     *
     * @return false if the key was reclaimed by another request in the meantime. The caller must then roll back
     *     whatever it did under the key.
     */
    public boolean complete(String key, UUID leaseToken, StoredResponse response) {
        int completed = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = :status, response_body = CAST(:body AS json),
                            resource_id = :resourceId
                        WHERE idem_key = :key AND lease_token = :token AND status = 'IN_PROGRESS'
                        """)
                .param("status", response.status())
                .param("body", response.body())
                .param("resourceId", response.resourceId(), Types.OTHER)
                .param("key", key)
                .param("token", leaseToken)
                .update();
        return completed == 1;
    }

    /** Ends the lease of {@code leaseToken} now, so the next request with the key can reclaim it without waiting. */
    public void release(String key, UUID leaseToken) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET locked_until = now()
                        WHERE idem_key = :key AND lease_token = :token AND status = 'IN_PROGRESS'
                        """).param("key", key).param("token", leaseToken).update();
    }

    /**
     * Deletes up to {@code limit} keys past their expiry, oldest first. Rows another transaction has locked are
     * skipped, so concurrent cleanups do not wait for each other.
     *
     * @return how many keys were deleted
     */
    public int deleteExpired(int limit) {
        return jdbc.sql("""
                        DELETE FROM idempotency_keys
                        WHERE idem_key IN (
                            SELECT idem_key FROM idempotency_keys
                            WHERE expires_at < now()
                            ORDER BY expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        """).param("limit", limit).update();
    }

    private static KeyRow toRow(ResultSet rs, int row) throws SQLException {
        KeyStatus status = KeyStatus.valueOf(rs.getString("status"));
        String body = rs.getString("response_body");
        StoredResponse response = body == null
                ? null
                : new StoredResponse(rs.getInt("response_status"), body, rs.getObject("resource_id", UUID.class));
        return new KeyRow(
                rs.getString("idem_key"),
                rs.getString("request_hash"),
                status,
                rs.getObject("lease_token", UUID.class),
                response);
    }
}
