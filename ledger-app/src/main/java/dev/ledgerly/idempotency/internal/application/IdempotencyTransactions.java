package dev.ledgerly.idempotency.internal.application;

import dev.ledgerly.idempotency.StoredResponse;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository.KeyRow;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two transactions of a request. They are methods of a bean of their own because Spring starts a transaction
 * only for calls that come in from another bean.
 */
@Service
class IdempotencyTransactions {

    /** The key was reclaimed by another request while the action ran. Thrown to roll the action back. */
    static final class LeaseLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LeaseLostException(String key) {
            super("lease on idempotency key " + key + " was taken over by another request");
        }
    }

    private final IdempotencyKeyRepository keys;
    private final FaultInjector faults;
    private final Duration lease;
    private final Duration ttl;

    IdempotencyTransactions(
            IdempotencyKeyRepository keys,
            ObjectProvider<FaultInjector> faults,
            @Value("${ledgerly.idempotency.lease:30s}") Duration lease,
            @Value("${ledgerly.idempotency.ttl:24h}") Duration ttl) {
        this.keys = keys;
        this.faults = faults.getIfAvailable(() -> FaultInjector.NONE);
        this.lease = lease;
        this.ttl = ttl;
    }

    /** First transaction: finds out who the key belongs to and takes it if it is free. */
    @Transactional
    Claim claim(String key, String requestHash) {
        UUID leaseToken = UUID.randomUUID();
        if (keys.insert(key, requestHash, leaseToken, lease, ttl)) {
            return new Claim.Held(leaseToken);
        }
        Optional<KeyRow> existing = keys.find(key);
        if (existing.isEmpty()) {
            // The cleanup deleted the key between the two statements. The client's retry will claim it afresh.
            return new Claim.HeldByAnother();
        }
        KeyRow row = existing.get();
        if (!row.requestHash().equals(requestHash)) {
            return new Claim.HashMismatch();
        }
        StoredResponse stored = row.response();
        if (stored != null) {
            return new Claim.Completed(stored);
        }
        // Still in progress. The update succeeds only if the holder's lease has run out and it has not completed
        // the key since the row was read.
        return keys.reclaim(key, leaseToken, lease) ? new Claim.Held(leaseToken) : new Claim.HeldByAnother();
    }

    /**
     * Second transaction: the action and the completion of the key commit together.
     *
     * @throws LeaseLostException if the key was reclaimed in the meantime. Everything the action did is rolled back,
     *     because the request that reclaimed the key runs the action as well.
     */
    @Transactional
    StoredResponse runAndComplete(String key, UUID leaseToken, Supplier<StoredResponse> action) {
        StoredResponse response = action.get();
        faults.beforeComplete(key);
        if (!keys.complete(key, leaseToken, response)) {
            throw new LeaseLostException(key);
        }
        return response;
    }
}
