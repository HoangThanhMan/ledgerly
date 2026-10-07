package dev.ledgerly.idempotency;

import java.util.function.Supplier;

/** Makes a request safe to retry: however often it is sent under one key, its action takes effect at most once. */
public interface IdempotencyApi {

    /**
     * Runs {@code action} under {@code key}, unless the key shows that the request was already handled or is being
     * handled right now.
     *
     * <p>The key is claimed in one short transaction. The action then runs in a second transaction, which also
     * stores its response with the key: both commit together or not at all. No transaction is open in between, so
     * an action may be slow without holding database locks while it waits to start.
     *
     * <p>If the action throws, its transaction is rolled back, nothing is stored and the key is released, so the
     * client may retry at once. The exception is rethrown. A rejection that should be answered the same way on every
     * retry must therefore be returned as a response, not thrown.
     *
     * <p>Must be called outside a transaction: inside one, the claim would not be committed before the action runs.
     *
     * @param key the client's {@code Idempotency-Key}
     * @param requestHash fingerprint of the request, see {@link RequestHasher}. A key stays bound to the request it
     *     was first used with.
     * @param action does the work and returns the response to store
     */
    IdempotencyResult execute(String key, String requestHash, Supplier<StoredResponse> action);
}
