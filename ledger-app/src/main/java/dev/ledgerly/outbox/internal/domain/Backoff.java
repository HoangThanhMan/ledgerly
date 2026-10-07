package dev.ledgerly.outbox.internal.domain;

import java.time.Duration;

/**
 * How long to leave a failing dependency alone before trying again: twice as long after every failure in a row, up
 * to a maximum. A broker that is down is not helped by being asked five times a second.
 */
public record Backoff(Duration initial, Duration max) {

    public Backoff {
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("initial delay must be positive, got " + initial);
        }
        if (initial.compareTo(max) > 0) {
            throw new IllegalArgumentException("initial delay " + initial + " is above the maximum " + max);
        }
    }

    /** The pause after {@code consecutiveFailures} failures in a row. Zero if there was none. */
    public Duration delayAfter(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return Duration.ZERO;
        }
        // Past 2^30 the delay is over the maximum for any sensible setting, and the shift below would overflow.
        int doublings = Math.min(consecutiveFailures - 1, 30);
        long factor = 1L << doublings;
        if (initial.toMillis() > max.toMillis() / factor) {
            return max;
        }
        return initial.multipliedBy(factor);
    }
}
