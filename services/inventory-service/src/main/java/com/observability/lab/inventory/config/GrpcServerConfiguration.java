package com.observability.lab.inventory.config;

import com.observability.lab.inventory.grpc.InventoryGrpcService;
import com.observability.lab.shared.grpc.GrpcFields;
import com.observability.lab.shared.grpc.GrpcServerInterceptorChain;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The gRPC listener: its port, its thread pool, its health service and its interceptor chain.
 *
 * <p>A separate listener rather than gRPC over the servlet container. Running it through Tomcat
 * would forfeit HTTP/2 flow control and the Netty transport gRPC is tuned for — and flow control is
 * not a detail here, it is what makes the client-streaming reconciliation back-pressure the producer
 * instead of buffering until the process dies.
 *
 * <p>The REST API is untouched and stays the public face: it is gateway-routed, JWT-protected at the
 * edge and used by clients that will never speak gRPC. Two transports over one application service,
 * deliberately.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerConfiguration {

    /**
     * The pool gRPC dispatches handlers onto.
     *
     * <p>Bounded in both dimensions, and bound to Micrometer under the name the
     * {@code GrpcExecutorSaturated} alert queries. This is the most important saturation signal the
     * gRPC hop has and the hardest to diagnose without it: requests are slow, CPU is low, heap is
     * fine, the database is idle and Tomcat's thread pool is empty — because the queue is in front
     * of a pool nothing else measures.
     *
     * <p>The queue is bounded so overload surfaces as {@code RESOURCE_EXHAUSTED}, which is retryable
     * with backoff and visible on a dashboard. An unbounded queue turns the same overload into
     * rising latency and eventually an {@code OutOfMemoryError}, which is neither.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor grpcExecutor(GrpcServerProperties properties, MeterRegistry registry) {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.executorThreads(),
                properties.executorThreads(),
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.executorQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            GrpcFields.EXECUTOR_NAME + "-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                // Rejection is a signal, not a surprise. Throwing lets the status mapper turn it
                // into RESOURCE_EXHAUSTED; the alternative policies either run the task on the
                // transport thread (blocking the event loop) or discard it silently.
                new ThreadPoolExecutor.AbortPolicy());

        ExecutorServiceMetrics.monitor(registry, executor, GrpcFields.EXECUTOR_NAME, Tags.empty());
        return executor;
    }

    /**
     * The gRPC health service, reporting the same state as {@code /actuator/health}.
     *
     * <p>Two protocols, one source of truth. A gRPC-native client asking
     * {@code grpc.health.v1.Health/Check} and Consul polling the actuator endpoint must not be able
     * to get different answers — that discrepancy is how half a fleet keeps receiving traffic it
     * cannot serve.
     */
    @Bean
    public HealthStatusManager grpcHealthStatusManager() {
        return new HealthStatusManager();
    }

    /**
     * The listener itself.
     *
     * <p>Server reflection is on by default and turned off under {@code prod}
     * (application-prod.yml). It publishes the full service schema — every method, every message,
     * every field — to anyone who can reach the port: in a lab that is exactly what makes
     * {@code grpcurl} usable without a copy of the {@code .proto}, and in production it is free
     * reconnaissance.
     */
    @Bean
    public Server grpcServer(GrpcServerProperties properties, InventoryGrpcService inventory,
            GrpcServerInterceptorChain interceptors, HealthStatusManager health,
            Executor grpcExecutor) {

        NettyServerBuilder builder = NettyServerBuilder
                .forAddress(new InetSocketAddress(properties.bindAddress(), properties.port()))
                .executor(grpcExecutor)
                .maxInboundMessageSize(properties.maxInboundMessageSize())
                // Refuse a client that pings more often than this. Without it a misbehaving or
                // hostile client can keep a connection busy with keepalives alone.
                .permitKeepAliveTime(20, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(interceptors.wrap(inventory))
                // Wrapped in the same chain: health checks are excluded from the logs and the
                // metrics by the interceptors themselves, which is the right place for that
                // decision rather than by leaving the service uninstrumented.
                .addService(interceptors.wrap(health.getHealthService()));

        if (properties.reflectionEnabled()) {
            // Not wrapped: reflection is a schema dump for a human with grpcurl, and putting it in
            // the RED metrics would make the call-rate panel report developer curiosity.
            builder.addService(ProtoReflectionServiceV1.newInstance());
        }
        return builder.build();
    }

    @Bean
    public GrpcServerLifecycle grpcServerLifecycle(Server grpcServer, GrpcServerProperties properties) {
        return new GrpcServerLifecycle(grpcServer, properties);
    }

    /**
     * Keeps the gRPC health service in step with Spring Boot's readiness state.
     *
     * <p>Readiness, not liveness: readiness is the one that answers "should traffic be routed here",
     * which is the same question {@code Health/Check} is asked.
     */
    @Component
    static class GrpcHealthBridge {

        private final HealthStatusManager health;

        GrpcHealthBridge(HealthStatusManager health) {
            this.health = health;
        }

        @EventListener
        void onReadinessChanged(AvailabilityChangeEvent<ReadinessState> event) {
            io.grpc.health.v1.HealthCheckResponse.ServingStatus status =
                    event.getState() == ReadinessState.ACCEPTING_TRAFFIC
                            ? io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING
                            : io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING;

            // The empty service name is the gRPC convention for "the whole server", which is what a
            // load balancer or a service mesh probes.
            health.setStatus("", status);
            health.setStatus("inventory.v1.InventoryService", status);
        }
    }
}
