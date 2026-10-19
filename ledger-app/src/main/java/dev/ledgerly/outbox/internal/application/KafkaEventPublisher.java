package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository.OutboxRow;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Puts one outbox event on Kafka, wrapped in the envelope of the contract, and waits until the broker has it. */
@Component
class KafkaEventPublisher {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Duration sendTimeout;

    @Autowired
    KafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            JsonMapper json,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator,
            @Value("${ledgerly.outbox.relay.send-timeout:5s}") Duration sendTimeout) {
        this(
                kafka,
                json,
                tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP),
                sendTimeout);
    }

    KafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            JsonMapper json,
            Tracer tracer,
            Propagator propagator,
            Duration sendTimeout) {
        this.kafka = kafka;
        this.json = json;
        this.tracer = tracer;
        this.propagator = propagator;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Sends the event and blocks until the broker acknowledges it. The aggregate id is the record key, so the
     * events of one aggregate land on one partition in the order they are sent.
     *
     * <p>Everything in the record's key and value comes from the row, so sending a row again produces the same
     * bytes, event id included. That is what lets a consumer recognize a copy.
     *
     * <p>The send runs in a span that continues the trace stored with the event, so the request that wrote the
     * event, this send and the consumer's handling show up as one trace.
     *
     * @throws EventPublishException if there is no acknowledgement within the timeout. The broker may still write
     *     the record later: the caller must be ready to have published it twice.
     */
    void publish(OutboxRow event) {
        String key = event.aggregateId().toString();
        Span span = propagator
                .extract(json.readValue(event.headers(), STRING_MAP), (carrier, name) -> carrier.get(name))
                .name("outbox publish")
                .tag("messaging.destination.name", event.topic())
                .tag("ledgerly.event.type", event.eventType())
                .start();
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            kafka.send(event.topic(), key, envelope(event)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.error(e);
            throw new EventPublishException(event.id(), event.topic(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            span.error(e);
            throw new EventPublishException(event.id(), event.topic(), e);
        } finally {
            span.end();
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
