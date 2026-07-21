package com.observability.lab.order.infrastructure.grpc;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * Resolves {@code consul:///<service-id>} to the gRPC endpoints of every healthy instance.
 *
 * <p>This is the piece that makes client-side load balancing possible, and it is the single most
 * important operational difference between gRPC and the REST setup alongside it.
 *
 * <p><strong>Why a proxy cannot do this job.</strong> A gRPC channel opens one long-lived HTTP/2
 * connection and multiplexes every RPC over it. A layer-4 load balancer therefore balances
 * <em>connections</em>, not requests — the connection is established once and pinned, so one
 * instance receives every RPC for the lifetime of that connection while its peers idle. Scaling out
 * changes nothing, and the symptom looks like a scheduling problem rather than a protocol one.
 *
 * <p>So the channel resolves and balances itself: this resolver watches Consul, and
 * {@code round_robin} in the channel's service config distributes per RPC.
 *
 * <p>In Kubernetes the same trap is differently shaped — a {@code ClusterIP} Service is L4, so pods
 * get pinned the same way, and the answers are a headless Service, a mesh sidecar, or xDS. Consul
 * plays that role here. The principle is identical: <strong>something must balance at layer 7, and
 * by default nothing does.</strong>
 */
public class DiscoveryClientNameResolverProvider extends NameResolverProvider {

    /** The URI scheme this provider claims: {@code consul:///inventory-service}. */
    public static final String SCHEME = "consul";

    /** Metadata key the provider publishes its gRPC port under. */
    static final String GRPC_PORT_METADATA = "grpc-port";

    private static final Logger log =
            LoggerFactory.getLogger(DiscoveryClientNameResolverProvider.class);

    private final DiscoveryClient discovery;
    private final long refreshSeconds;

    public DiscoveryClientNameResolverProvider(DiscoveryClient discovery, long refreshSeconds) {
        this.discovery = discovery;
        this.refreshSeconds = refreshSeconds;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 5;
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    public NameResolver newNameResolver(URI target, NameResolver.Args args) {
        if (!SCHEME.equals(target.getScheme())) {
            // Returning null rather than throwing: gRPC asks every registered provider in turn, and
            // a provider that throws on a scheme it does not own breaks the ones that do.
            return null;
        }
        String serviceId = target.getPath().replaceFirst("^/", "");
        return new DiscoveryClientNameResolver(serviceId, discovery, args, refreshSeconds);
    }

    /**
     * Polls the registry and hands the channel the current address set.
     *
     * <p>Polling rather than a blocking Consul watch. The registry is already polled by Spring
     * Cloud's own discovery client, whose cache this reads, so a second long-poll connection per
     * channel would add load to Consul for information the process already has.
     */
    static final class DiscoveryClientNameResolver extends NameResolver {

        private final String serviceId;
        private final DiscoveryClient discovery;
        private final ScheduledExecutorService scheduler;
        private final long refreshSeconds;

        private Listener2 listener;
        private ScheduledFuture<?> polling;
        private List<EquivalentAddressGroup> lastResolved = List.of();

        DiscoveryClientNameResolver(String serviceId, DiscoveryClient discovery,
                NameResolver.Args args, long refreshSeconds) {
            this.serviceId = serviceId;
            this.discovery = discovery;
            this.scheduler = args.getScheduledExecutorService();
            this.refreshSeconds = refreshSeconds;
        }

        @Override
        public String getServiceAuthority() {
            return serviceId;
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;
            resolve();
            this.polling = scheduler.scheduleWithFixedDelay(
                    this::resolve, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }

        /** Called by the channel when it suspects the address set is stale, e.g. after a failure. */
        @Override
        public void refresh() {
            resolve();
        }

        @Override
        public void shutdown() {
            if (polling != null) {
                polling.cancel(false);
            }
        }

        private void resolve() {
            List<EquivalentAddressGroup> addresses;
            try {
                addresses = grpcEndpointsOf(discovery.getInstances(serviceId));
            } catch (RuntimeException failure) {
                // The registry itself is unreachable. Reported as UNAVAILABLE so the channel keeps
                // using the addresses it already has rather than dropping them - a Consul blip must
                // not take out a hop whose endpoints have not actually moved.
                listener.onError(Status.UNAVAILABLE
                        .withDescription("could not resolve '" + serviceId + "' through the registry")
                        .withCause(failure));
                return;
            }

            if (addresses.isEmpty()) {
                listener.onError(Status.UNAVAILABLE
                        .withDescription("no healthy '" + serviceId + "' instance advertises a gRPC port"));
                return;
            }

            if (!addresses.equals(lastResolved)) {
                log.info("Resolved {} gRPC endpoint(s) for '{}'", addresses.size(), serviceId);
                lastResolved = addresses;
            }
            listener.onResult(ResolutionResult.newBuilder().setAddresses(addresses).build());
        }

        /**
         * Keeps only the instances that actually advertise a gRPC port.
         *
         * <p>An instance that has not been upgraded yet registers without the metadata, and offering
         * it as a gRPC target would produce connection refusals that look like an outage. Filtering
         * makes a rolling upgrade a non-event.
         */
        private static List<EquivalentAddressGroup> grpcEndpointsOf(List<ServiceInstance> instances) {
            List<EquivalentAddressGroup> addresses = new ArrayList<>(instances.size());
            for (ServiceInstance instance : instances) {
                String port = instance.getMetadata().get(GRPC_PORT_METADATA);
                if (port == null || port.isBlank()) {
                    continue;
                }
                try {
                    addresses.add(new EquivalentAddressGroup(
                            new InetSocketAddress(instance.getHost(), Integer.parseInt(port.trim()))));
                } catch (NumberFormatException malformed) {
                    log.warn("Instance {} advertises an unusable {}: '{}'",
                            instance.getInstanceId(), GRPC_PORT_METADATA, port);
                }
            }
            return addresses;
        }
    }
}
