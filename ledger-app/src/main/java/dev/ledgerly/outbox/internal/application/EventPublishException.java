package dev.ledgerly.outbox.internal.application;

import java.util.UUID;

/** Kafka did not acknowledge an event in time, or refused it. The event may or may not have been written. */
public class EventPublishException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    EventPublishException(UUID eventId, String topic, Throwable cause) {
        super("event " + eventId + " was not acknowledged by topic " + topic, cause);
    }
}
