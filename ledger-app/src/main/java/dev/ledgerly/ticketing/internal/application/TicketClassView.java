package dev.ledgerly.ticketing.internal.application;

import java.util.UUID;

/**
 * A class of tickets of an event, without what sales change.
 *
 * @param price minor units of VND
 */
public record TicketClassView(UUID id, String name, long price, int totalQty) {}
