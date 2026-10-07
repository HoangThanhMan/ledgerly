package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.outbox.internal.domain.Backoff;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay a few times a second, and leaves Kafka alone for a growing while when it fails.
 *
 * <p>Switched on by default. An instance started with {@code ledgerly.outbox.relay.enabled=false} serves the API
 * and leaves relaying to other instances. Several relaying instances do not get in each other's way, see
 * {@link dev.ledgerly.outbox.internal.persistence.OutboxEventRepository#lockNextBatch}.
 */
@Component
@ConditionalOnProperty(name = "ledgerly.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final Backoff backoff;
    private final Clock clock;

    // Written and read by the scheduler, which starts a run only after the previous one has returned.
    private volatile int consecutiveFailures;
    private volatile Instant pausedUntil = Instant.MIN;

    @Autowired
    OutboxRelayScheduler(
            OutboxRelay relay,
            @Value("${ledgerly.outbox.relay.backoff.initial:200ms}") Duration initialBackoff,
            @Value("${ledgerly.outbox.relay.backoff.max:30s}") Duration maxBackoff) {
        this(relay, new Backoff(initialBackoff, maxBackoff), Clock.systemUTC());
    }

    OutboxRelayScheduler(OutboxRelay relay, Backoff backoff, Clock clock) {
        this.relay = relay;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ledgerly.outbox.relay.poll-interval:200ms}")
    void poll() {
        if (clock.instant().isBefore(pausedUntil)) {
            return;
        }
        try {
            relay.drain();
            consecutiveFailures = 0;
        } catch (RuntimeException e) {
            int failures = consecutiveFailures + 1;
            Duration pause = backoff.delayAfter(failures);
            consecutiveFailures = failures;
            pausedUntil = clock.instant().plus(pause);
            // The events stay in the outbox, so this is a delay and not a loss. The stack trace is kept for debug.
            log.warn("Relay failed {} times in a row, next try in {} ms: {}", failures, pause.toMillis(), e.toString());
            log.debug("Relay failure", e);
        }
    }
}
