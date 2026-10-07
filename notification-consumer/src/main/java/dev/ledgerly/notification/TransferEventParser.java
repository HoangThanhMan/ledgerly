package dev.ledgerly.notification;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.TransferCompleted;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the messages of the transfers topic. Fields this consumer does not know are ignored, so the producer can add
 * fields without breaking it.
 */
@Component
class TransferEventParser {

    private final JsonMapper json;
    private final JavaType envelopeType;

    TransferEventParser(JsonMapper json) {
        this.json = json;
        this.envelopeType = json.getTypeFactory().constructParametricType(EventEnvelope.class, TransferCompleted.class);
    }

    /**
     * The event in the message, or empty if it is an event of another type, which is none of this consumer's
     * business.
     *
     * @throws MalformedEventException if the message is not JSON, or lacks what is needed to handle it
     */
    Optional<EventEnvelope<TransferCompleted>> parse(String message) {
        EventEnvelope<TransferCompleted> event;
        try {
            JsonNode root = json.readTree(message);
            JsonNode type = root.path("eventType");
            if (!type.isString()) {
                throw new MalformedEventException("message has no eventType", null);
            }
            if (!TransferCompleted.EVENT_TYPE.equals(type.asString())) {
                return Optional.empty();
            }
            event = json.treeToValue(root, envelopeType);
        } catch (JacksonException e) {
            throw new MalformedEventException("message is not a readable event: " + e.getOriginalMessage(), e);
        }
        if (isIncomplete(event)) {
            throw new MalformedEventException("event lacks its id, its payload or the wallets in it", null);
        }
        return Optional.of(event);
    }

    // The records of the contract declare no field as optional, but JSON can leave any of them out.
    @SuppressWarnings("NullAway")
    private static boolean isIncomplete(EventEnvelope<TransferCompleted> event) {
        TransferCompleted transfer = event.payload();
        return event.eventId() == null
                || transfer == null
                || transfer.sourceWalletId() == null
                || transfer.targetWalletId() == null
                || transfer.amount() == null
                || transfer.currency() == null;
    }
}
