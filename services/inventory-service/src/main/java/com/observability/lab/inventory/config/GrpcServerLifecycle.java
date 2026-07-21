package com.observability.lab.inventory.config;

import io.grpc.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Binds the gRPC port when the context is ready, and drains it before the context goes away.
 *
 * <p>A {@link SmartLifecycle} rather than {@code @Bean(initMethod, destroyMethod)} for two reasons
 * that both matter at the edges of a process's life:
 *
 * <ul>
 *   <li><strong>It starts late and stops early.</strong> The phase puts it after the datasource, the
 *       cache and the Kafka containers, so the port does not accept a call the service cannot yet
 *       serve — and before them on the way down, so nothing arrives after its dependencies have
 *       closed.
 *   <li><strong>It can refuse to shut down instantly.</strong> {@code shutdown()} stops accepting
 *       new calls and lets in-flight ones finish, which is the difference between a rolling restart
 *       being invisible and it producing a burst of UNAVAILABLE.
 * </ul>
 */
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final Server server;
    private final GrpcServerProperties properties;
    private volatile boolean running;

    public GrpcServerLifecycle(Server server, GrpcServerProperties properties) {
        this.server = server;
        this.properties = properties;
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
            log.info("gRPC server listening on {}:{}", properties.bindAddress(), properties.port());
        } catch (IOException failure) {
            // Fatal on purpose. A service that silently starts without its gRPC listener registers
            // itself in Consul as healthy and then refuses every RPC routed to it.
            throw new IllegalStateException(
                    "Could not bind the gRPC port " + properties.port(), failure);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Draining the gRPC server, up to {}s", properties.shutdownGrace().toSeconds());
        server.shutdown();
        try {
            if (!server.awaitTermination(properties.shutdownGrace().toSeconds(), TimeUnit.SECONDS)) {
                log.warn("gRPC calls were still in flight after the grace period; forcing shutdown");
                server.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Late in the start-up order, early in the shutdown order.
     *
     * <p>{@code DEFAULT_PHASE - 1024} rather than the default: Spring starts lower phases first and
     * stops higher phases first, so a phase just below the default would be wrong in one direction.
     * This value puts the listener after the infrastructure it depends on and closes it before that
     * infrastructure is torn down.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }
}
