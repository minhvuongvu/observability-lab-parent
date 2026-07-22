package com.observability.lab.shared.chaos;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Wraps the real cache manager so cache operations can be made to fail or to miss, while Redis itself
 * stays perfectly healthy.
 *
 * <p>Worth being precise about why this exists when {@code chaos.sh down redis} already works.
 * Toxiproxy breaks the <em>connection</em>: Lettuce notices, the health indicator goes down, readiness
 * fails, and the gateway takes the instance out of rotation. That is one failure, and a loud one.
 *
 * <p>This is the quiet one. The connection is fine, the health check is green, the instance keeps
 * serving traffic — and every cache lookup either throws or misses. The service stays "up" by every
 * measure the platform has while its origin load multiplies. Cache stampedes look like this, and so
 * does a misconfigured serializer, and neither is reachable from the network layer.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li>{@code fail} — operations throw, so the caller's error handling is what is under test
 *   <li>{@code miss} — reads return null, so every request falls through to the origin. This is the
 *       more interesting one: nothing errors, nothing alerts, and the database quietly takes the
 *       full uncached load.
 * </ul>
 */
public class ChaosCacheManager implements CacheManager {

    public static final String FAULT = "cache";
    public static final String MODE_FAIL = "fail";
    public static final String MODE_MISS = "miss";

    private final CacheManager delegate;

    /**
     * Resolved lazily, and that is a wiring constraint rather than a preference.
     *
     * <p>This decorator is applied by a {@link org.springframework.beans.factory.config.BeanPostProcessor},
     * which the container instantiates very early. Taking the registry as a constructor argument
     * would drag {@code ChaosRegistry} — and through it {@code MeterRegistry} — into that early
     * phase, which is the classic way to end up with a half-configured metrics registry that
     * silently drops the common tags every other meter carries.
     */
    private final Supplier<ChaosRegistry> registry;

    public ChaosCacheManager(CacheManager delegate, Supplier<ChaosRegistry> registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    @Nullable
    public Cache getCache(@NonNull String name) {
        Cache cache = delegate.getCache(name);
        return cache == null ? null : new ChaosCache(cache);
    }

    @Override
    @NonNull
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    private String activeMode() {
        ChaosRegistry current = registry.get();
        if (current == null || !current.shouldApply(FAULT)) {
            return null;
        }
        return current.find(FAULT)
                .map(t -> String.valueOf(t.attributes().getOrDefault("mode", MODE_MISS)))
                .orElse(null);
    }

    private final class ChaosCache implements Cache {

        private final Cache delegate;

        private ChaosCache(Cache delegate) {
            this.delegate = delegate;
        }

        @Override
        @NonNull
        public String getName() {
            return delegate.getName();
        }

        @Override
        @NonNull
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        @Nullable
        public ValueWrapper get(@NonNull Object key) {
            String mode = activeMode();
            if (MODE_FAIL.equals(mode)) {
                throw new ChaosInjectedException("chaos: cache read failed for key " + key);
            }
            if (MODE_MISS.equals(mode)) {
                return null;
            }
            return delegate.get(key);
        }

        @Override
        @Nullable
        public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
            String mode = activeMode();
            if (MODE_FAIL.equals(mode)) {
                throw new ChaosInjectedException("chaos: cache read failed for key " + key);
            }
            if (MODE_MISS.equals(mode)) {
                return null;
            }
            return delegate.get(key, type);
        }

        @Override
        @Nullable
        public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
            String mode = activeMode();
            if (MODE_FAIL.equals(mode)) {
                throw new ChaosInjectedException("chaos: cache read failed for key " + key);
            }
            if (MODE_MISS.equals(mode)) {
                // Load without caching the result, so the next request misses too. Delegating here
                // would repopulate the entry and the fault would heal itself after one request.
                try {
                    return valueLoader.call();
                } catch (Exception e) {
                    throw new ValueRetrievalException(key, valueLoader, e);
                }
            }
            return delegate.get(key, valueLoader);
        }

        @Override
        public void put(@NonNull Object key, @Nullable Object value) {
            String mode = activeMode();
            if (MODE_FAIL.equals(mode)) {
                throw new ChaosInjectedException("chaos: cache write failed for key " + key);
            }
            if (MODE_MISS.equals(mode)) {
                return;
            }
            delegate.put(key, value);
        }

        @Override
        public void evict(@NonNull Object key) {
            // Eviction is never faulted. A chaos mode that prevented invalidation would leave stale
            // data behind after the experiment ended, which is a correctness bug masquerading as a
            // fault - and the one kind of damage this lab must not do.
            delegate.evict(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
