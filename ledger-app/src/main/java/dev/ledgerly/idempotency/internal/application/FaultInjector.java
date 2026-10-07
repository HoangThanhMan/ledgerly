package dev.ledgerly.idempotency.internal.application;

/**
 * The two points of a request where a crash or a stall is hard to produce on demand. The application has no bean of
 * this type, so nothing happens there. Tests register one to break a request at exactly that point.
 */
public interface FaultInjector {

    FaultInjector NONE = new FaultInjector() {};

    /**
     * Called after the key was claimed and before the action starts. An exception thrown here leaves the key
     * {@code IN_PROGRESS} with its lease running, the way a crash of the process would.
     */
    default void afterClaim(String key) {}

    /** Called inside the action's transaction, after the action returned and before the key is completed. */
    default void beforeComplete(String key) {}
}
