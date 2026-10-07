package dev.ledgerly.idempotency.internal.application;

import dev.ledgerly.idempotency.StoredResponse;
import java.util.UUID;

/** What claiming a key found. */
sealed interface Claim {

    /** The caller now holds the key under this lease token and must run the action. */
    record Held(UUID leaseToken) implements Claim {}

    /** The key is completed with this response. */
    record Completed(StoredResponse response) implements Claim {}

    /** The key belongs to a request with another hash. */
    record HashMismatch() implements Claim {}

    /** Another request holds the key and its lease is still running. */
    record HeldByAnother() implements Claim {}
}
