package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.idempotency.IdempotencyApi;
import dev.ledgerly.idempotency.IdempotencyResult;
import dev.ledgerly.idempotency.RequestHasher;
import dev.ledgerly.idempotency.StoredResponse;
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
 * <p>The answer of an endpoint is rendered to JSON inside the action, so it is stored in the same transaction as
 * the money movement, and a retry gets back the very text the first request got. This covers rejections as well:
 * a transfer refused for lack of funds stays refused under its key, even if the wallet is topped up later.
 */
@Component
class IdempotentRequests {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** What an endpoint answers. Stored with the key, so it must not depend on anything but the action. */
    sealed interface Reply {

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
     * @param body the validated request body: together with method and path it identifies the request
     * @param location where a created resource can be read, or null if the endpoint has no such address
     * @param action does the work. A rejection must be returned as {@link Reply.Rejected}: an exception rolls the
     *     action back and stores nothing.
     */
    ResponseEntity<String> execute(
            String key,
            HttpServletRequest http,
            Object body,
            @Nullable Function<UUID, URI> location,
            Supplier<Reply> action) {
        String path = http.getRequestURI();
        String requestHash = hasher.hash(http.getMethod(), path, body);
        return switch (idempotency.execute(key, requestHash, () -> stored(action.get(), path))) {
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
