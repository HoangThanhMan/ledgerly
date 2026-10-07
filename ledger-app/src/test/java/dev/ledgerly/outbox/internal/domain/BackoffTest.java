package dev.ledgerly.outbox.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30));

    @Test
    void delayDoublesWithEveryConsecutiveFailure() {
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofMillis(800));
        assertThat(backoff.delayAfter(8)).isEqualTo(Duration.ofMillis(25_600));
    }

    @Test
    void delayNeverExceedsTheMaximum() {
        assertThat(backoff.delayAfter(9)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.delayAfter(64)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void thereIsNoDelayWithoutAFailure() {
        assertThat(backoff.delayAfter(0)).isEqualTo(Duration.ZERO);
    }

    @Test
    void initialDelayMustBePositiveAndNotAboveTheMaximum() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
