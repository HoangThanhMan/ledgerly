package dev.ledgerly.idempotency;

import java.time.Duration;

/** What a key resolved to for one call of {@link IdempotencyApi#execute}. */
public sealed interface IdempotencyResult {

    /** The action ran in this call. Its response is now stored with the key. */
    record Executed(StoredResponse response) implements IdempotencyResult {}

    /** An earlier call completed the key. This is the response it stored: the action did not run again. */
    record Replayed(StoredResponse response) implements IdempotencyResult {}

    /** The key was first used with a different request. The action did not run. */
    record KeyReused() implements IdempotencyResult {}

    /**
     * Another call holds the key and has not finished, so there is no response to give yet. Nothing this call did
     * took effect. The client should send the request again after {@code retryAfter}.
     */
    record InProgress(Duration retryAfter) implements IdempotencyResult {}
}
