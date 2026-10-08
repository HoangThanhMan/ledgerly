package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class OutboxService implements OutboxWriter {

    private final OutboxEventRepository events;
    private final JsonMapper json;
    private final Tracer tracer;
    private final Propagator propagator;

    OutboxService(
            OutboxEventRepository events,
            JsonMapper json,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<Propagator> propagator) {
        this.events = events;
        this.json = json;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    // MANDATORY: joins the caller's transaction and fails if there is none, instead of quietly opening its own.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        events.insert(
                event.topic(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event.payload()),
                json.writeValueAsString(traceContext()));
    }

    /**
     * The trace the caller is in, in the form it travels between services ({@code traceparent}). The relay publishes
     * the event later and on another thread, where nothing else connects it to the request that caused it. Empty if
     * the caller is not being traced.
     */
    private Map<String, String> traceContext() {
        Map<String, String> headers = new LinkedHashMap<>();
        Span current = tracer.currentSpan();
        if (current != null) {
            propagator.inject(current.context(), headers, (carrier, key, value) -> headers.put(key, value));
        }
        return headers;
    }
}
