package dev.ledgerly.ticketing.internal.application;

/** A ticket class of an event that is being created. */
public record NewTicketClass(String name, long price, int totalQty) {}
