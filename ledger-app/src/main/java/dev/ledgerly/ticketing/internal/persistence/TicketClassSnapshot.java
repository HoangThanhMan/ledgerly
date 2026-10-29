package dev.ledgerly.ticketing.internal.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * What a purchase needs to know about a ticket class and its event, read without a lock: none of it changes while
 * tickets are on sale, except the start of the event.
 *
 * @param price minor units of VND
 */
public record TicketClassSnapshot(
        UUID id, String name, long price, UUID eventId, String eventName, UUID payoutWalletId, Instant startsAt) {}
