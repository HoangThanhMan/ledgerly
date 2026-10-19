package dev.ledgerly.outbox.internal.application;

import java.util.List;
import java.util.UUID;

/**
 * The point of a relay round where a crash is hard to produce on demand. The application has no bean of this type,
 * so nothing happens there. Tests register one to break a round at exactly that point.
 */
public interface OutboxFaults {

    OutboxFaults NONE = new OutboxFaults() {};

    /**
     * Called after Kafka has acknowledged every event of the batch and before they are marked as published. An
     * exception thrown here leaves the database as it would be if the process had died at this point.
     */
    default void afterSend(List<UUID> eventIds) {}
}
