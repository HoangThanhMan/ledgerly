package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * How far the relay is behind. The gauges ask the database when they are read, not when the relay runs: a relay
 * that is stuck or switched off is exactly when these numbers matter.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(OutboxEventRepository events, MeterRegistry meters) {
        Gauge.builder("ledgerly.outbox.pending", events, OutboxEventRepository::countPending)
                .description("Events written to the outbox and not yet published")
                .register(meters);
        TimeGauge.builder(
                        "ledgerly.outbox.oldest.age",
                        events,
                        TimeUnit.MILLISECONDS,
                        repository -> repository.oldestPendingAge().toMillis())
                .description("How long the oldest unpublished event has been waiting")
                .register(meters);
    }
}
