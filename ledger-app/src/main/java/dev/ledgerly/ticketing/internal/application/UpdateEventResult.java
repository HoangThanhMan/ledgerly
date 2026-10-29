package dev.ledgerly.ticketing.internal.application;

/** Outcome of editing an event. */
public sealed interface UpdateEventResult {

    record Updated(EventDetails event) implements UpdateEventResult {}

    /** There is no such event, or it is not the caller's. The two are deliberately not told apart. */
    record NotFound() implements UpdateEventResult {}
}
