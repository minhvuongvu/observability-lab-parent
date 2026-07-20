package com.observability.lab.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.inventory.application.StockApplicationService;
import com.observability.lab.inventory.application.StockLevelView;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis caching for the stock read path.
 *
 * <p>Values are stored as JSON bound to a known type rather than through Java serialisation or
 * polymorphic type information: a serializer that reads a type name out of the payload and
 * instantiates it is a deserialisation gadget waiting for anyone with write access to Redis. Naming
 * the type here means a cached entry can only ever become a {@link StockLevelView}.
 *
 * <p>The TTL is shorter than the Order Service's. Stock changes far more often than an order does,
 * and a stale availability figure is the one that oversells.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {

    /** Every key this service writes is prefixed, so a shared Redis stays navigable. */
    private static final String KEY_PREFIX = "inventory-service:";

    @Bean
    public RedisCacheManagerBuilderCustomizer stockCacheCustomizer(
            ObjectMapper objectMapper, @Value("${app.cache.stock-ttl}") Duration stockTtl) {

        // A copy: mutating the shared mapper would change how every HTTP response renders too.
        ObjectMapper cacheMapper = objectMapper.copy();

        RedisCacheConfiguration stockConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(stockTtl)
                .disableCachingNullValues()
                .prefixCacheNameWith(KEY_PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(cacheMapper,
                                StockLevelView.class)));

        return builder ->
                builder.withCacheConfiguration(StockApplicationService.STOCK_CACHE, stockConfig);
    }
}
