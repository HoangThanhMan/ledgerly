package dev.ledgerly.shared.problem;

/** Ends request handling with a Problem Details response of the given type. The message becomes its detail. */
public class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemType type;

    public ProblemException(ProblemType type, String detail) {
        super(detail);
        this.type = type;
    }

    public ProblemType type() {
        return type;
    }
}
