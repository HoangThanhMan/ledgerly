package dev.ledgerly.identity.internal.application;

import java.time.Duration;

/** Outcome of a sign-in. */
public sealed interface LoginResult {

    record LoggedIn(String accessToken, Duration expiresIn) implements LoginResult {}

    /** The email is unknown or the password is wrong. Which of the two is deliberately not told. */
    record InvalidCredentials() implements LoginResult {}
}
