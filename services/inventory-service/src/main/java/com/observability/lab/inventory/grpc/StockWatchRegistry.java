package com.observability.lab.inventory.grpc;

import com.observability.lab.inventory.application.StockChangedEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans stock changes out to the clients currently watching.
 *
 * <p>Every subscription is a held resource — a thread-free one, but a resource nonetheless: an open
 * HTTP/2 stream, an entry in this registry, and a slot in the {@code grpc_server_active_calls} gauge.
 * They leak silently if nothing removes them, which is why the subscription is removed on
 * cancellation, on completion <em>and</em> on a delivery failure.
 *
 * <p>Delivery is after commit, deliberately. Announcing a level that is still inside an open
 * transaction means a client can be told a number that is then rolled back, and a live subscription
 * has no mechanism to take a value back.
 */
@Component
public class StockWatchRegistry {

    private static final Logger log = LoggerFactory.getLogger(StockWatchRegistry.class);

    /**
     * Keyed by nothing: every subscription filters for itself.
     *
     * <p>An index by SKU would be faster and would be wrong to build yet — a watchlist is a set, a
     * SKU can be on many watchlists, and maintaining the inverted index correctly under concurrent
     * subscribe and unsubscribe is more machinery than a lab's subscription count justifies. The
     * cost is a scan per change over a set measured in tens.
     */
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    /**
     * Registers interest in a set of SKUs.
     *
     * @param productSkus what the client wants to hear about
     * @param sink        called for each matching change; must not block
     * @return a handle that removes the subscription, to be called from the stream's teardown
     */
    public AutoCloseable subscribe(Set<String> productSkus, Consumer<StockChangedEvent> sink) {
        Subscription subscription = new Subscription(Set.copyOf(productSkus), sink);
        subscriptions.add(subscription);
        log.debug("Stock watch opened for {} sku(s); {} subscription(s) now active",
                productSkus.size(), subscriptions.size());

        return () -> {
            subscriptions.remove(subscription);
            log.debug("Stock watch closed; {} subscription(s) remain", subscriptions.size());
        };
    }

    /** How many streams are open. Exposed for the readiness of the operator, not for the client. */
    public int activeSubscriptions() {
        return subscriptions.size();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onStockChanged(StockChangedEvent event) {
        for (Subscription subscription : subscriptions) {
            if (!subscription.productSkus.contains(event.productSku())) {
                continue;
            }
            try {
                subscription.sink.accept(event);
            } catch (RuntimeException failure) {
                // One dead subscriber must not stop the others being told. A client that has gone
                // away without cancelling cleanly is the normal case, not the exceptional one.
                subscriptions.remove(subscription);
                log.debug("Dropping a stock watch that could not be written to: {}",
                        failure.toString());
            }
        }
    }

    /**
     * Identity is the instance, not the contents.
     *
     * <p>Two clients watching the same SKUs are two subscriptions, and a record's value equality
     * would collapse them into one — so the second client to subscribe would unsubscribe the first
     * when it disconnected.
     */
    private static final class Subscription {

        private final Set<String> productSkus;
        private final Consumer<StockChangedEvent> sink;

        private Subscription(Set<String> productSkus, Consumer<StockChangedEvent> sink) {
            this.productSkus = productSkus;
            this.sink = sink;
        }
    }
}
