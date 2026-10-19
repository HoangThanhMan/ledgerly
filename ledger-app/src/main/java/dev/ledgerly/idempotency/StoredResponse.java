package dev.ledgerly.idempotency;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The answer to a request, as it is kept with its idempotency key and returned again on every replay.
 *
 * @param status the HTTP status
 * @param body the response body, as JSON text
 * @param resourceId id of what the request created, or null if it created nothing
 */
public record StoredResponse(
        int status, String body, @Nullable UUID resourceId) {}
