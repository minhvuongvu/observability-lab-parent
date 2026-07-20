package com.observability.lab.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.order.application.OrderView;
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
 * Redis caching for the order read path.
 *
 * <p>Values are stored as JSON bound to a known type rather than through Java serialisation or
 * polymorphic type information. Three reasons, in order of importance:
 *
 * <ol>
 *   <li><strong>Security.</strong> A serializer that reads a type name out of the payload and
 *       instantiates it is a deserialisation gadget waiting for someone with write access to Redis.
 *       Naming the type here means the payload can only ever become an {@link OrderView}.
 *   <li><strong>Legibility.</strong> {@code redis-cli GET} returns something a human can read, which
 *       matters in a lab whose subject is observing what the system is doing.
 *   <li><strong>Evolution.</strong> Adding a field does not invalidate cached entries the way a
 *       changed {@code serialVersionUID} would.
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {

    /**
     * Every key this service writes is prefixed, so a shared Redis stays navigable and one service
     * cannot collide with another's key space.
     */
    private static final String KEY_PREFIX = "order-service:";

    @Bean
    public RedisCacheManagerBuilderCustomizer ordersCacheCustomizer(
            ObjectMapper objectMapper, @Value("${app.cache.orders-ttl}") Duration ordersTtl) {

        // A copy: mutating the shared mapper would change how every HTTP response is rendered too.
        ObjectMapper cacheMapper = objectMapper.copy();

        RedisCacheConfiguration ordersConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ordersTtl)
                // A TTL is not optional. Without one an entry outlives its correctness and the only
                // remedy is flushing the cache by hand.
                .disableCachingNullValues()
                .prefixCacheNameWith(KEY_PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(cacheMapper, OrderView.class)));

        return builder ->
                builder.withCacheConfiguration(OrderApplicationService.ORDERS_CACHE, ordersConfig);
    }
}
