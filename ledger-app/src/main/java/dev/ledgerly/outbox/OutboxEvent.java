package dev.ledgerly.outbox;

import java.util.UUID;

/**
 * An event to publish once the current transaction has committed.
 *
 * @param topic Kafka topic the event goes to
 * @param aggregateType kind of thing the event is about
 * @param aggregateId id of that thing. Events with the same id reach consumers in the order they were appended.
 * @param eventType name of the payload type
 * @param eventVersion version of the payload schema
 * @param payload the event, written to JSON when it is appended
 */
public record OutboxEvent(
        String topic, String aggregateType, UUID aggregateId, String eventType, int eventVersion, Object payload) {}
