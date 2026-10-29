package dev.ledgerly.ticketing.internal.application;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as the event list shows it.
 *
 * @param minPrice price of the cheapest ticket class, in minor units of VND
 */
public record EventSummary(UUID id, String name, Instant startsAt, long minPrice) {}
