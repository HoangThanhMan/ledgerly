package dev.ledgerly.outbox.internal.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ledgerly.outbox.internal.domain.Backoff;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxRelaySchedulerTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final OutboxRelay relay = mock(OutboxRelay.class);
    private final ManualClock clock = new ManualClock();
    private final OutboxRelayScheduler scheduler =
            new OutboxRelayScheduler(relay, new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30)), clock);

    @Test
    void everyPollDrainsTheBacklogWhileTheRelayWorks() {
        scheduler.poll();
        scheduler.poll();

        verify(relay, times(2)).drain();
    }

    @Test
    void afterAFailureThePollsAreSkippedUntilTheBackoffHasPassed() {
        when(relay.drain()).thenThrow(new IllegalStateException("broker down")).thenReturn(0);

        scheduler.poll();
        clock.advance(Duration.ofMillis(199));
        scheduler.poll();
        verify(relay, times(1)).drain();

        clock.advance(Duration.ofMillis(1));
        scheduler.poll();
        verify(relay, times(2)).drain();
    }

    @Test
    void backoffGrowsWhileTheRelayKeepsFailingAndIsForgottenOnceItWorksAgain() {
        when(relay.drain())
                .thenThrow(new IllegalStateException("broker down"))
                .thenThrow(new IllegalStateException("broker still down"))
                .thenReturn(3)
                .thenThrow(new IllegalStateException("broker down again"))
                .thenReturn(0);

        scheduler.poll();
        clock.advance(Duration.ofMillis(200));
        scheduler.poll();
        // Second failure in a row: the pause is now 400 ms, so 200 ms later nothing happens.
        clock.advance(Duration.ofMillis(200));
        scheduler.poll();
        verify(relay, times(2)).drain();

        clock.advance(Duration.ofMillis(200));
        scheduler.poll();
        verify(relay, times(3)).drain();

        // It worked, so the next failure starts again from the short pause.
        scheduler.poll();
        clock.advance(Duration.ofMillis(200));
        scheduler.poll();
        verify(relay, times(5)).drain();
    }
}
