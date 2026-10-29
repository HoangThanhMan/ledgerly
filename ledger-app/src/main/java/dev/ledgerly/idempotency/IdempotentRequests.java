package dev.ledgerly.idempotency;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the endpoints that move money under their {@code Idempotency-Key}, and turns what the key resolved to into
 * the HTTP response.
 *
 * <p>A key is the client's name for one of its own operations, so it is scoped to the user who sent it: two users who
 * pick the same key name two different operations, and neither can replay the other's response.
 *
 * <p>The answer of an endpoint is rendered to JSON inside the action, so it is stored in the same transaction as
 * the money movement, and a retry gets back the very text the first request got. This covers rejections as well:
 * a transfer refused for lack of funds stays refused under its key, even if the wallet is topped up later.
 */
@Component
public final class IdempotentRequests {

    public static final String KEY_HEADER = "Idempotency-Key";

    /** 1 to 64 visible ASCII characters. A UUID is what clients are expected to send. */
    public static final String KEY_PATTERN = "[\\x21-\\x7E]{1,64}";

    public static final String KEY_MESSAGE = "must be 1 to 64 visible ASCII characters";

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** Documentation of the {@code Idempotency-Key} header, for the endpoints that take it. */
    public static final String KEY_DOC = """
            Identifies this operation, so that it is safe to send again. Generate one value for each operation \
            (a UUID is recommended) and send that same value on every retry of it. 1 to 64 visible ASCII \
            characters. The key and the response stored under it are kept for at least 24 hours. Keys are \
            per user: another user's key with the same value is a different key.""";

    /** Documentation of the {@code Idempotent-Replayed} response header. */
    public static final String REPLAYED_DOC = """
            Present with the value `true` when this is the stored response of an earlier request with the same \
            Idempotency-Key, and nothing was done again.""";

    /** What an endpoint answers. Stored with the key, so it must not depend on anything but the action. */
    public sealed interface Reply {

        /** 201 with the representation of what was created. */
        record Created(UUID resourceId, Object body) implements Reply {}

        /** A business rejection. The exception only carries the problem here, it is not thrown. */
        record Rejected(ProblemException problem) implements Reply {}
    }

    private final IdempotencyApi idempotency;
    private final RequestHasher hasher;
    private final JsonMapper json;

    IdempotentRequests(IdempotencyApi idempotency, RequestHasher hasher, JsonMapper json) {
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.json = json;
    }

    /**
     * Runs the action once per key of the caller, and answers every request with that key with its response.
     *
     * @param scope whose key it is, normally the id of the signed-in user
     * @param key the client's {@code Idempotency-Key}, as sent
     * @param body the validated request body: together with method and path it identifies the request
     * @param location where a created resource can be read, or null if the endpoint has no such address
     * @param action does the work. A rejection must be returned as {@link Reply.Rejected}: an exception rolls the
     *     action back and stores nothing.
     */
    public ResponseEntity<String> execute(
            UUID scope,
            String key,
            HttpServletRequest http,
            Object body,
            @Nullable Function<UUID, URI> location,
            Supplier<Reply> action) {
        String path = http.getRequestURI();
        String requestHash = hasher.hash(http.getMethod(), path, body);
        String scopedKey = scope + ":" + key;
        return switch (idempotency.execute(scopedKey, requestHash, () -> stored(action.get(), path))) {
            case IdempotencyResult.Executed executed -> render(executed.response(), false, location);
            case IdempotencyResult.Replayed replayed -> render(replayed.response(), true, location);
            case IdempotencyResult.KeyReused reused ->
                throw new ProblemException(
                        ProblemType.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key " + key + " belongs to a request with a different method, path or body");
            case IdempotencyResult.InProgress inProgress ->
                throw new ProblemException(
                        ProblemType.IDEMPOTENCY_IN_PROGRESS,
                        "Another request with Idempotency-Key " + key + " has not finished yet. Retry this one.",
                        inProgress.retryAfter());
        };
    }

    private StoredResponse stored(Reply reply, String path) {
        return switch (reply) {
            case Reply.Created created ->
                new StoredResponse(
                        HttpStatus.CREATED.value(), json.writeValueAsString(created.body()), created.resourceId());
            case Reply.Rejected rejected -> {
                ProblemException exception = rejected.problem();
                ProblemDetail problem = exception.type().toProblemDetail(exception.getMessage());
                problem.setInstance(URI.create(path));
                yield new StoredResponse(problem.getStatus(), json.writeValueAsString(problem), null);
            }
        };
    }

    private static ResponseEntity<String> render(
            StoredResponse stored, boolean replayed, @Nullable Function<UUID, URI> location) {
        boolean problem = HttpStatus.valueOf(stored.status()).isError();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.status())
                .contentType(problem ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
        if (replayed) {
            response.header(REPLAYED_HEADER, "true");
        }
        UUID resourceId = stored.resourceId();
        if (location != null && resourceId != null) {
            response.location(location.apply(resourceId));
        }
        return response.body(stored.body());
    }
}
