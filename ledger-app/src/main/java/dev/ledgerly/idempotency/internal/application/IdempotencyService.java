package dev.ledgerly.idempotency.internal.application;

import dev.ledgerly.idempotency.IdempotencyApi;
import dev.ledgerly.idempotency.IdempotencyResult;
import dev.ledgerly.idempotency.StoredResponse;
import dev.ledgerly.idempotency.internal.application.IdempotencyTransactions.LeaseLostException;
import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class IdempotencyService implements IdempotencyApi {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyTransactions transactions;
    private final IdempotencyKeyRepository keys;
    private final FaultInjector faults;
    private final Duration retryAfter;
    private final Counter replays;

    IdempotencyService(
            IdempotencyTransactions transactions,
            IdempotencyKeyRepository keys,
            ObjectProvider<FaultInjector> faults,
            @Value("${ledgerly.idempotency.retry-after:1s}") Duration retryAfter,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.keys = keys;
        this.faults = faults.getIfAvailable(() -> FaultInjector.NONE);
        this.retryAfter = retryAfter;
        this.replays = Counter.builder("ledgerly.idempotency.replays")
                .description("Requests answered with the stored response of an earlier request with the same key")
                .register(meters);
    }

    @Override
    public IdempotencyResult execute(String key, String requestHash, Supplier<StoredResponse> action) {
        return switch (transactions.claim(key, requestHash)) {
            case Claim.Held held -> runHolding(key, held.leaseToken(), action);
            case Claim.Completed completed -> {
                replays.increment();
                yield new IdempotencyResult.Replayed(completed.response());
            }
            case Claim.HashMismatch mismatch -> new IdempotencyResult.KeyReused();
            case Claim.HeldByAnother another -> new IdempotencyResult.InProgress(retryAfter);
        };
    }

    private IdempotencyResult runHolding(String key, UUID leaseToken, Supplier<StoredResponse> action) {
        faults.afterClaim(key);
        try {
            return new IdempotencyResult.Executed(transactions.runAndComplete(key, leaseToken, action));
        } catch (LeaseLostException e) {
            log.warn("Rolled back a request that outlived its lease: {}", e.getMessage());
            return new IdempotencyResult.InProgress(retryAfter);
        } catch (RuntimeException e) {
            release(key, leaseToken, e);
            throw e;
        }
    }

    /**
     * The action failed and was rolled back, so nothing happened yet. Ending the lease lets the client retry at
     * once. If this fails too, the lease still runs out by itself.
     */
    private void release(String key, UUID leaseToken, RuntimeException failure) {
        try {
            keys.release(key, leaseToken);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
            log.warn("Could not release idempotency key {} after a failed request", key, releaseFailure);
        }
    }
}
