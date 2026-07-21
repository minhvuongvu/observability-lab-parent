package com.observability.lab.shared.grpc;

import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.Tags;

/**
 * The identity of one RPC, split once per call instead of on every log line and every meter lookup.
 *
 * <p>{@code MethodDescriptor.getFullMethodName()} is a single string —
 * {@code inventory.v1.InventoryService/BatchCheckStock} — and every interceptor needs the two halves
 * separately. Splitting it four times per call, in four interceptors, is the kind of cost that is
 * invisible until the RPC rate is high enough for it not to be.
 *
 * @param service fully qualified service name, version included
 * @param method  the RPC name alone
 * @param type    UNARY, SERVER_STREAMING, CLIENT_STREAMING or BIDI_STREAMING
 */
public record GrpcMethodRef(String service, String method, MethodDescriptor.MethodType type) {

    public static GrpcMethodRef of(MethodDescriptor<?, ?> descriptor) {
        String full = descriptor.getFullMethodName();
        String service = MethodDescriptor.extractFullServiceName(full);
        String method = service == null ? full : full.substring(service.length() + 1);
        return new GrpcMethodRef(service == null ? "unknown" : service, method, descriptor.getType());
    }

    /**
     * Whether this is a health probe.
     *
     * <p>Excluded from logging and tracing entirely. On a service probed every ten seconds by three
     * checkers, health checks are the single largest producer of both, and every one of them is
     * noise. That is not an optimisation — it is the difference between a usable log store and one
     * where the signal is a rounding error.
     */
    public boolean isHealthCheck() {
        return GrpcFields.HEALTH_SERVICE.equals(service);
    }

    /** The three dimensions every gRPC meter carries. All bounded, by construction. */
    public Tags tags() {
        return Tags.of(
                GrpcFields.GRPC_SERVICE, service,
                GrpcFields.GRPC_METHOD, method,
                GrpcFields.GRPC_TYPE, type.name());
    }
}
