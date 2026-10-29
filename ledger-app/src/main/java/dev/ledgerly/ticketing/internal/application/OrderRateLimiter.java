package dev.ledgerly.ticketing.internal.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Limits how many orders each user may send a minute, counted in Redis. A flash sale is when a few clients send
 * requests as fast as they can; the limit keeps them from taking the database's time from everyone else.
 *
 * <p>The limit fails open: when Redis is down or hangs, every order is allowed and counted in
 * {@code ledgerly.ratelimit.bypassed}. Losing the limit for a while is better than losing the sales.
 */
@Component
public class OrderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimiter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    @SuppressWarnings("rawtypes") // Redis returns the script's table as a List of Long.
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("redis/rate-limit.lua"), List.class);

    private final StringRedisTemplate redis;
    private final int perMinute;
    private final Counter bypassed;

    OrderRateLimiter(
            StringRedisTemplate redis,
            @Value("${ledgerly.ticketing.rate-limit.per-minute:10}") int perMinute,
            MeterRegistry meters) {
        this.redis = redis;
        this.perMinute = perMinute;
        this.bypassed = Counter.builder("ledgerly.ratelimit.bypassed")
                .description("Orders let through without a rate limit check because Redis failed")
                .register(meters);
    }

    /** Counts one order of the user and decides whether it may go ahead. */
    public Decision check(UUID userId) {
        try {
            List<?> result =
                    redis.execute(SCRIPT, List.of("ratelimit:orders:" + userId), Long.toString(WINDOW.toSeconds()));
            long count = (Long) result.get(0);
            long secondsLeft = (Long) result.get(1);
            return count <= perMinute
                    ? new Decision.Allowed()
                    : new Decision.Limited(Duration.ofSeconds(Math.max(secondsLeft, 1)));
        } catch (RuntimeException e) {
            bypassed.increment();
            log.warn("Rate limit not checked, order allowed: {}", e.toString());
            return new Decision.Allowed();
        }
    }

    /** Whether an order may go ahead. */
    public sealed interface Decision {

        record Allowed() implements Decision {}

        /**
         * The user sent too many orders.
         *
         * @param retryAfter when the user's window ends and orders are accepted again
         */
        record Limited(Duration retryAfter) implements Decision {}
    }
}
