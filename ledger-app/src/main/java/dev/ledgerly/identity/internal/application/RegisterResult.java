package dev.ledgerly.identity.internal.application;

/** Outcome of a sign-up. */
public sealed interface RegisterResult {

    record Registered(UserView user) implements RegisterResult {}

    record EmailTaken(String email) implements RegisterResult {}
}
