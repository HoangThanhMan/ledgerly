package dev.ledgerly.ticketing.internal.application;

import java.util.UUID;

/** The tickets left in a class, as subscribers of {@code /topic/ticket-classes/{id}} receive them. */
public record RemainingMessage(UUID ticketClassId, int remaining) {}
