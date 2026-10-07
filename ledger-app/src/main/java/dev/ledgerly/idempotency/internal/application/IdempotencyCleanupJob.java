package dev.ledgerly.idempotency.internal.application;

import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes keys past their expiry, so the table holds about a day of requests instead of growing forever.
 *
 * <p>A key is kept for at least its time to live, not exactly that long: it keeps replaying its response until a
 * run of this job removes it. Several application instances may run the job at the same time, each batch skips the
 * rows another instance is deleting.
 */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyKeyRepository keys;
    private final int batchSize;

    IdempotencyCleanupJob(
            IdempotencyKeyRepository keys, @Value("${ledgerly.idempotency.cleanup.batch-size:1000}") int batchSize) {
        this.keys = keys;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${ledgerly.idempotency.cleanup.interval:10m}",
            initialDelayString = "${ledgerly.idempotency.cleanup.interval:10m}")
    void deleteExpiredKeys() {
        int deleted = deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency keys", deleted);
        }
    }

    /**
     * Deletes every expired key, one batch per transaction. Small transactions keep row locks short and never hold
     * back the requests that are claiming keys meanwhile.
     *
     * @return how many keys were deleted
     */
    int deleteExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = keys.deleteExpired(batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
