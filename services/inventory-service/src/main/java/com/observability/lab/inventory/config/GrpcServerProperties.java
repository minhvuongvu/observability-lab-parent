package com.observability.lab.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service's gRPC listener is configured.
 *
 * @param port                 the gRPC port. {@code 90xx} mirrors the service's {@code 80xx} HTTP
 *                             port, so the pairing is obvious at a glance
 * @param bindAddress          the interface to bind. Loopback in the lab: gRPC here is internal and
 *                             is deliberately not routed through Kong, which is a security property
 *                             rather than an oversight
 * @param executorThreads      size of the handler pool. gRPC dispatches onto its own executor,
 *                             separate from Tomcat's, and the separation is the point — a pool
 *                             exhausted here queues RPCs while the HTTP pool sits idle
 * @param executorQueueCapacity how many RPCs may wait for a handler thread. Bounded, so the failure
 *                             mode under overload is RESOURCE_EXHAUSTED rather than an unbounded
 *                             queue that turns into an OutOfMemoryError
 * @param shutdownGrace        how long a shutdown waits for in-flight calls to finish
 * @param maxInboundMessageSize largest request accepted. The default 4 MiB; a larger limit is a
 *                             memory-exhaustion vector, and a batch of 100 SKUs is kilobytes
 * @param reflectionEnabled    whether to serve the schema over server reflection, so {@code grpcurl}
 *                             works without a copy of the {@code .proto}. Off in production:
 *                             reflection publishes the full service schema to anyone who can reach
 *                             the port
 */
@ConfigurationProperties(prefix = "app.grpc.server")
public record GrpcServerProperties(
        int port,
        String bindAddress,
        int executorThreads,
        int executorQueueCapacity,
        Duration shutdownGrace,
        int maxInboundMessageSize,
        boolean reflectionEnabled) {

    public GrpcServerProperties {
        port = port == 0 ? 9082 : port;
        bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
        executorThreads = executorThreads == 0 ? 16 : executorThreads;
        executorQueueCapacity = executorQueueCapacity == 0 ? 256 : executorQueueCapacity;
        shutdownGrace = shutdownGrace == null ? Duration.ofSeconds(20) : shutdownGrace;
        maxInboundMessageSize = maxInboundMessageSize == 0 ? 4 * 1024 * 1024 : maxInboundMessageSize;
    }
}
