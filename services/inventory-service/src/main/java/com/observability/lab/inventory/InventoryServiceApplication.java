package com.observability.lab.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Inventory Service.
 *
 * <p>The Inventory Service owns stock levels and uses Oracle XE as its system of record. It reacts
 * to the {@code order-created} event, continues the distributed trace across the Kafka boundary and
 * publishes {@code inventory-updated} back to the Order Service.
 *
 * @see <a href="../../../../../../../../docs/Architecture.md">docs/Architecture.md</a>
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
