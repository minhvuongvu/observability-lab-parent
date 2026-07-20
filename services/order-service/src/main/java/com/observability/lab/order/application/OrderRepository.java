package com.observability.lab.order.application;

import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * What the application layer needs from storage, expressed without naming a storage technology.
 *
 * <p>Declared here rather than in {@code domain} because it is the application layer that decides
 * which outbound capabilities its use cases require. The implementation lives in
 * {@code infrastructure.persistence} and is the only place Spring Data appears.
 *
 * <p>Every lookup is by order number, never by the surrogate id: the id is an implementation detail
 * of the table and nothing outside persistence should depend on it.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * Finds orders matching the supplied filters.
     *
     * @param customerId restrict to one customer, or {@code null} for all
     * @param status     restrict to one status, or {@code null} for all
     */
    Page<Order> search(String customerId, OrderStatus status, Pageable pageable);

    void delete(Order order);
}
