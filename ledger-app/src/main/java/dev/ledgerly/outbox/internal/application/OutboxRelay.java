package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository.OutboxRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox to Kafka, at least once each.
 *
 * <p>One round is one database transaction: lock a batch of unpublished events, send them in order and wait for
 * each acknowledgement, mark them as published, commit. If anything fails, the transaction rolls back and the
 * whole batch is published again by a later round. An event that Kafka already took is then published twice.
 * Losing one is not possible, publishing one twice is: consumers deduplicate on the event id.
 *
 * <p>The transaction stays open while waiting for Kafka. That is acceptable here and nowhere else: one background
 * thread holds one connection, the locks are on outbox rows only, and every send has a timeout.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository events;
    private final KafkaEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final OutboxFaults faults;
    private final int batchSize;

    @Autowired
    OutboxRelay(
            OutboxEventRepository events,
            KafkaEventPublisher publisher,
            TransactionTemplate transaction,
            ObjectProvider<OutboxFaults> faults,
            @Value("${ledgerly.outbox.relay.batch-size:100}") int batchSize) {
        this.events = events;
        this.publisher = publisher;
        this.transaction = transaction;
        this.faults = faults.getIfAvailable(() -> OutboxFaults.NONE);
        this.batchSize = batchSize;
    }

    /** Runs rounds until the backlog is empty, and returns how many events were published. */
    int drain() {
        int total = 0;
        int published;
        do {
            published = relayBatch();
            total += published;
        } while (published == batchSize);
        return total;
    }

    /**
     * Publishes one batch of the oldest unpublished events.
     *
     * @return how many events were published. Less than the batch size means the backlog is empty.
     * @throws EventPublishException if Kafka did not take an event. Nothing was marked as published.
     */
    int relayBatch() {
        List<UUID> attempted = new ArrayList<>();
        try {
            Integer published = transaction.execute(status -> {
                List<OutboxRow> batch = events.lockNextBatch(batchSize);
                // In order, one acknowledgement at a time: if an event fails, no later one has been sent.
                for (OutboxRow event : batch) {
                    attempted.add(event.id());
                    publisher.publish(event);
                }
                faults.afterSend(attempted);
                events.markPublished(attempted);
                return batch.size();
            });
            return published == null ? 0 : published;
        } catch (RuntimeException e) {
            countFailedAttempt(attempted, e);
            throw e;
        }
    }

    /** After the rollback, in a statement of its own: inside the failed transaction the count would be undone. */
    private void countFailedAttempt(List<UUID> attempted, RuntimeException failure) {
        if (attempted.isEmpty()) {
            return;
        }
        try {
            events.recordFailedAttempt(attempted);
        } catch (RuntimeException countFailure) {
            failure.addSuppressed(countFailure);
            log.warn("Could not count the failed attempt on {} outbox events", attempted.size(), countFailure);
        }
    }
}
