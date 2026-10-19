package dev.ledgerly.shared.problem;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Ends request handling with a Problem Details response of the given type. The message becomes its detail. */
public class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemType type;
    private final @Nullable Duration retryAfter;

    public ProblemException(ProblemType type, String detail) {
        this(type, detail, null);
    }

    /**
     * @param retryAfter when the same request is worth sending again, sent as the {@code Retry-After} header. Null
     *     if retrying the request unchanged cannot succeed.
     */
    public ProblemException(ProblemType type, String detail, @Nullable Duration retryAfter) {
        super(detail);
        this.type = type;
        this.retryAfter = retryAfter;
    }

    public ProblemType type() {
        return type;
    }

    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
