package dev.ledgerly.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * What every event on Kafka is wrapped in. The fields outside the payload are the same for all events, so a consumer
 * can deduplicate, route and order events without knowing their payload.
 *
 * @param eventId unique per event and stable across redeliveries: consumers deduplicate on it
 * @param eventType name of the payload type, such as {@code TransferCompleted}
 * @param eventVersion version of the payload schema. Adding an optional field does not change it.
 * @param occurredAt when the change the event reports was made
 * @param aggregateType kind of thing the event is about, such as {@code LedgerTransaction}
 * @param aggregateId id of that thing. It is the record key, so its events stay in order on one partition.
 * @param payload the event itself
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        T payload) {}
