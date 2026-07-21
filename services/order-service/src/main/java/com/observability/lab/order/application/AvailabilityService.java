package com.observability.lab.order.application;

import com.observability.lab.order.infrastructure.client.InventoryClient;
import com.observability.lab.order.infrastructure.grpc.InventoryGrpcClient;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Asks the Inventory Service what is available, synchronously — over either transport.
 *
 * <p>A <em>question</em> cannot be answered by an event. Placing an order is asynchronous because
 * the Order Service must keep accepting orders while Inventory is down; asking "is this in stock
 * right now" has no useful asynchronous form, so it accepts the coupling that comes with waiting.
 *
 * <p>Both implementations are kept, and that is the point of this class. The same logical operation
 * runs over REST and over gRPC against the same provider, so the comparison is measurable rather
 * than asserted:
 *
 * <table border="1">
 *   <caption>25-line basket</caption>
 *   <tr><th></th><th>REST</th><th>gRPC</th></tr>
 *   <tr><td>Round trips</td><td>25</td><td>1</td></tr>
 *   <tr><td>Contract</td><td>OpenAPI, hand-copied DTO</td><td>{@code .proto}, generated both sides</td></tr>
 *   <tr><td>Encoding</td><td>JSON text</td><td>Protobuf binary</td></tr>
 *   <tr><td>Provider query</td><td>25 point lookups</td><td>one {@code IN} predicate</td></tr>
 * </table>
 *
 * <p>Advisory either way. Nothing here reserves stock, and a caller that skips the check gets
 * exactly the same guarantees; the authoritative decision is the Inventory Service's response to
 * {@code order-created}.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final InventoryClient inventory;

    /**
     * Absent when the gRPC hop is switched off.
     *
     * <p>An {@link ObjectProvider} rather than a required dependency, so turning gRPC off leaves a
     * service that still starts and still answers over REST — which is what makes the flag a real
     * fallback rather than a way to break the build.
     */
    private final ObjectProvider<InventoryGrpcClient> grpc;

    public AvailabilityService(InventoryClient inventory, ObjectProvider<InventoryGrpcClient> grpc) {
        this.inventory = inventory;
        this.grpc = grpc;
    }

    /** Which transport a caller asked for. */
    public enum Transport {
        /** One round trip for the whole basket, over the versioned proto contract. */
        GRPC,
        /** One HTTP request per line. Retained, and deliberately still the honest implementation. */
        REST
    }

    /**
     * Checks each requested line against current stock.
     *
     * <p>Falls back to REST when gRPC was asked for and is not configured, rather than failing: the
     * question has a perfectly good answer over the other transport, and refusing to give it because
     * of a configuration flag would be a worse outcome than a slower reply.
     */
    public List<AvailabilityView> check(List<CreateOrderCommand.Line> lines, Transport transport) {
        if (transport == Transport.GRPC) {
            InventoryGrpcClient client = grpc.getIfAvailable();
            if (client != null) {
                return client.batchCheck(lines);
            }
            log.debug("gRPC was requested but is not enabled; answering over REST");
        }
        return checkOverRest(lines);
    }

    /** The default transport: one round trip regardless of basket size. */
    public List<AvailabilityView> check(List<CreateOrderCommand.Line> lines) {
        return check(lines, Transport.GRPC);
    }

    /**
     * The REST path, unchanged.
     *
     * <p>One call per SKU, sequentially — which is honest, and is exactly the cost the gRPC contract
     * exists to remove. It is kept rather than tidied away because a comparison needs both sides to
     * be real.
     */
    private List<AvailabilityView> checkOverRest(List<CreateOrderCommand.Line> lines) {
        return lines.stream().map(this::checkOne).toList();
    }

    private AvailabilityView checkOne(CreateOrderCommand.Line line) {
        try {
            var response = inventory.getStockLevel(line.productSku());
            var stock = response.data();
            if (stock == null) {
                // A 2xx whose envelope carries no payload. Treating it as "unknown" rather than as
                // zero available keeps a protocol surprise from reading like a stock-out.
                log.warn("Inventory returned no payload for '{}'", line.productSku());
                return AvailabilityView.unknown(line.productSku(), line.quantity());
            }
            return AvailabilityView.of(line.productSku(), line.quantity(), stock.availableQuantity());

        } catch (ResourceNotFoundException unknown) {
            // Translated by the Feign error decoder from a 404. Not a failure of this call.
            log.debug("Inventory does not track '{}'", line.productSku());
            return AvailabilityView.unknown(line.productSku(), line.quantity());
        }
    }
}
