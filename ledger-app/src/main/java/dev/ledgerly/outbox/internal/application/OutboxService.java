package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class OutboxService implements OutboxWriter {

    private final OutboxEventRepository events;
    private final JsonMapper json;

    OutboxService(OutboxEventRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
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
                json.writeValueAsString(event.payload()));
    }
}
