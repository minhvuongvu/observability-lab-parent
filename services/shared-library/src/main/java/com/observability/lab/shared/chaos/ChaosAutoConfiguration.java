package com.observability.lab.shared.chaos;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the chaos endpoints, and refuses to do so anywhere they do not belong.
 *
 * <h2>Three independent guards</h2>
 *
 * <ol>
 *   <li><strong>Profile.</strong> {@code local} and {@code dev} only. Under {@code prod} none of
 *       these beans exist, so the paths 404 rather than 403 — a 403 confirms an endpoint is there,
 *       which is information a prod deployment should not volunteer.
 *   <li><strong>Property.</strong> {@code app.chaos.enabled}, defaulting to true <em>within those
 *       profiles</em>. A shared dev environment that would rather not have a colleague deadlocking
 *       it can switch the whole feature off without a rebuild.
 *   <li><strong>Role.</strong> {@code ADMIN}, enforced in the shared resource server configuration.
 * </ol>
 *
 * <p>Three guards for one feature is not paranoia proportionate to the risk — it is proportionate to
 * the consequence. Any one of these endpoints reachable by an unauthenticated caller in production is
 * a denial of service with an API reference.
 *
 * <p>The services themselves contribute the dependency-coupled faults by publishing a
 * {@link DatabaseChaos}, {@link MessagingChaos} or {@link ResilienceChaos} bean. Those beans are
 * profile-guarded in the services for the same reason, and the controller reports which of them are
 * present through {@code GET /api/v1/chaos}.
 */
@AutoConfiguration
@Profile({"local", "dev"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.chaos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChaosRegistry chaosRegistry(ChaosProperties properties) {
        return new ChaosRegistry(properties);
    }

    /**
     * Publishes {@code lab_chaos_active_faults}, the series that says a symptom was deliberate.
     *
     * <p>The most important meter this feature has. An injected fault and a real one are identical
     * in every other signal, so without this a dashboard screenshot from an experiment cannot be
     * told apart from a screenshot of an incident. Overlay it on any panel to answer "was that us?".
     *
     * <p>A {@link MeterBinder} rather than a {@code Gauge.builder(...).register(registry)} in the
     * registry's constructor: this auto-configuration runs before Micrometer contributes the
     * registry, so an injected {@code MeterRegistry} is null at that point and the gauge is never
     * created - silently, with nothing failing and the series simply absent. Spring Boot applies
     * MeterBinder beans once the registry exists.
     */
    @Bean
    public MeterBinder chaosActiveGauge(ChaosRegistry registry) {
        return meterRegistry -> Gauge.builder(
                        "lab.chaos.active", registry.activeCounter(), AtomicInteger::doubleValue)
                .description("Chaos faults currently injected. Non-zero means a symptom is deliberate")
                // Micrometer appends the base unit to the Prometheus name, so this meter is
                // scraped as lab_chaos_active_FAULTS. Worth stating: every dashboard query and
                // every mention in docs/FailureSimulation.md uses the suffixed form, and looking
                // for the unsuffixed name finds nothing.
                .baseUnit("faults")
                .register(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessChaos processChaos(ChaosProperties properties) {
        return new ProcessChaos(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChaosController chaosController(
            ChaosRegistry registry,
            ProcessChaos process,
            ChaosProperties properties,
            ObjectProvider<DatabaseChaos> databaseChaos,
            ObjectProvider<MessagingChaos> messagingChaos,
            ObjectProvider<ResilienceChaos> resilienceChaos) {
        return new ChaosController(
                registry, process, properties, databaseChaos, messagingChaos, resilienceChaos);
    }

    /**
     * Registered at the very front of the chain.
     *
     * <p>Before security, deliberately. Injected latency should reproduce a service that is slow for
     * <em>every</em> caller, including the ones about to be rejected with a 401 — because in a real
     * overload the authentication filter is queued behind the same exhausted thread pool as
     * everything else. A latency filter that ran after authorization would model something that does
     * not happen.
     */
    @Bean
    public FilterRegistrationBean<ChaosRequestFilter> chaosRequestFilter(ChaosRegistry registry) {
        FilterRegistrationBean<ChaosRequestFilter> registration =
                new FilterRegistrationBean<>(new ChaosRequestFilter(registry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * Registers the exception fault inside the dispatcher.
     *
     * <p>The registry is taken through an {@link ObjectProvider} and resolved per request rather than
     * injected: a {@code WebMvcConfigurer} is consulted during MVC setup, and pulling the registry -
     * and through it the meter registry - into that phase is how the common metric tags end up
     * missing from a fraction of the meters.
     */
    @Bean
    public WebMvcConfigurer chaosWebMvcConfigurer(ObjectProvider<ChaosRegistry> registry) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(@NonNull InterceptorRegistry interceptors) {
                interceptors.addInterceptor(new ChaosHandlerInterceptor(registry.getObject()));
            }
        };
    }

    /**
     * Wraps whatever cache manager the service configured.
     *
     * <p>A {@link BeanPostProcessor} rather than a {@code @Primary} bean, because a {@code
     * CacheManager} bean that takes a {@code CacheManager} argument is a bean that gets injected with
     * itself. Post-processing sidesteps the cycle entirely: the real manager is built exactly as the
     * service declared it and is handed to the decorator on the way out.
     *
     * <p>{@code static}, which matters. A non-static {@code BeanPostProcessor} factory method forces
     * its enclosing configuration class — and everything that class injects — to be instantiated
     * before the container is ready to do so, and Spring logs a warning about exactly that.
     *
     * <p>The registry is passed as a supplier over the bean factory, so nothing here is resolved
     * until the first cache operation. See the note on {@link ChaosCacheManager}.
     */
    @Bean
    static BeanPostProcessor chaosCacheManagerPostProcessor(ConfigurableListableBeanFactory beanFactory) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
                if (bean instanceof CacheManager cacheManager && !(bean instanceof ChaosCacheManager)) {
                    return new ChaosCacheManager(
                            cacheManager, () -> beanFactory.getBeanProvider(ChaosRegistry.class).getIfAvailable());
                }
                return bean;
            }
        };
    }
}
