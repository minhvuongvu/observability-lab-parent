package com.observability.lab.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Order Service.
 *
 * <p>The Order Service owns the order lifecycle and uses PostgreSQL as its system of record. In the
 * end-to-end business flow it is the first service behind the gateway, which makes it the origin of
 * the distributed trace and the producer of the {@code order-created} event.
 *
 * @see <a href="../../../../../../../../docs/Architecture.md">docs/Architecture.md</a>
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
