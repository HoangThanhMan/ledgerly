package dev.ledgerly.ticketing.internal.application;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event reads are cached in Redis for {@link #TTL}. The cache only saves database reads: when Redis fails or hangs,
 * the error is logged and the read goes to the database, so Redis is never needed to serve a request.
 *
 * <p>The cache advice runs outside the transaction advice: a hit opens no transaction and takes no connection.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
class CacheConfig implements CachingConfigurer {

    static final String EVENT = "event";
    static final String EVENTS_PAGE = "events-page";

    /** How stale a cached read can be when an eviction was lost, e.g. because Redis was down during an edit. */
    static final Duration TTL = Duration.ofSeconds(30);

    @Bean
    RedisCacheManagerBuilderCustomizer eventCaches(JsonMapper json) {
        // Statistics feed the cache.gets metric. Naming the caches here creates them at startup, so they have
        // metrics before their first read.
        return builder -> builder.enableStatistics()
                .withCacheConfiguration(EVENT, cacheOf(json, EventView.class))
                .withCacheConfiguration(EVENTS_PAGE, cacheOf(json, EventPage.class));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /** Values as JSON of one known type: no class names stored in Redis, and nothing else can be read back. */
    private static RedisCacheConfiguration cacheOf(JsonMapper json, Class<?> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(json, type)));
    }
}
