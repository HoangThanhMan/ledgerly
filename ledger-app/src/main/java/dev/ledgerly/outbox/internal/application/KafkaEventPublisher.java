package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository.OutboxRow;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Puts one outbox event on Kafka, wrapped in the envelope of the contract, and waits until the broker has it. */
@Component
class KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Duration sendTimeout;

    KafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            JsonMapper json,
            @Value("${ledgerly.outbox.relay.send-timeout:5s}") Duration sendTimeout) {
        this.kafka = kafka;
        this.json = json;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Sends the event and blocks until the broker acknowledges it. The aggregate id is the record key, so the
     * events of one aggregate land on one partition in the order they are sent.
     *
     * <p>Everything in the record comes from the row, so sending a row again produces the same bytes, event id
     * included. That is what lets a consumer recognize a copy.
     *
     * @throws EventPublishException if there is no acknowledgement within the timeout. The broker may still write
     *     the record later: the caller must be ready to have published it twice.
     */
    void publish(OutboxRow event) {
        String key = event.aggregateId().toString();
        try {
            kafka.send(event.topic(), key, envelope(event)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(event.id(), event.topic(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new EventPublishException(event.id(), event.topic(), e);
        }
    }

    private String envelope(OutboxRow event) {
        JsonNode payload = json.readTree(event.payload());
        return json.writeValueAsString(new EventEnvelope<>(
                event.id(),
                event.eventType(),
                event.eventVersion(),
                event.createdAt(),
                event.aggregateType(),
                event.aggregateId(),
                payload));
    }
}
