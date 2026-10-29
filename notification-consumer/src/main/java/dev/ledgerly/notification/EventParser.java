package dev.ledgerly.notification;

import dev.ledgerly.contracts.EventEnvelope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the messages of the event topics. Fields this consumer does not know are ignored, so the producer can add
 * fields without breaking it.
 */
@Component
class EventParser {

    private final JsonMapper json;

    EventParser(JsonMapper json) {
        this.json = json;
    }

    /**
     * The event in the message, or empty if it is an event of another type, which is none of this caller's business.
     *
     * @param eventType the type the caller handles, such as {@code TransferCompleted}
     * @param payloadType the contract record of that type. Every field of it must be present.
     * @throws MalformedEventException if the message is not JSON, or lacks what is needed to handle it
     */
    <T extends Record> Optional<EventEnvelope<T>> parse(String message, String eventType, Class<T> payloadType) {
        EventEnvelope<T> event;
        try {
            JsonNode root = json.readTree(message);
            JsonNode type = root.path("eventType");
            if (!type.isString()) {
                throw new MalformedEventException("message has no eventType", null);
            }
            if (!eventType.equals(type.asString())) {
                return Optional.empty();
            }
            JavaType envelopeType = json.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
            event = json.treeToValue(root, envelopeType);
        } catch (JacksonException e) {
            throw new MalformedEventException("message is not a readable event: " + e.getOriginalMessage(), e);
        }
        if (isIncomplete(event)) {
            throw new MalformedEventException("event lacks its id, its payload or a field of the payload", null);
        }
        return Optional.of(event);
    }

    // The records of the contract declare no field as optional, but JSON can leave any of them out.
    @SuppressWarnings("NullAway")
    private static boolean isIncomplete(EventEnvelope<? extends Record> event) {
        Record payload = event.payload();
        if (event.eventId() == null || payload == null) {
            return true;
        }
        for (RecordComponent field : payload.getClass().getRecordComponents()) {
            if (valueOf(field, payload) == null) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Object valueOf(RecordComponent field, Record payload) {
        try {
            return field.getAccessor().invoke(payload);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + field.getName() + " of " + payload.getClass(), e);
        }
    }
}
