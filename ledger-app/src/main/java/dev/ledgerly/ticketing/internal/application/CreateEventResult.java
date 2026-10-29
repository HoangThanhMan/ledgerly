package dev.ledgerly.ticketing.internal.application;

import java.util.UUID;

/** Outcome of creating an event. */
public sealed interface CreateEventResult {

    record Created(EventDetails event) implements CreateEventResult {}

    /** Only organizers create events. */
    record Forbidden() implements CreateEventResult {}

    /** The wallet is not one of the organizer's wallets. */
    record PayoutWalletInvalid(UUID walletId) implements CreateEventResult {}
}
