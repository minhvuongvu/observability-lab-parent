package com.observability.lab.order.infrastructure.persistence;

import com.observability.lab.order.domain.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data access to the orders table.
 *
 * <p>The only type in the service that names Spring Data. Everything above it depends on the
 * {@code OrderRepository} port, which this is adapted to by {@code OrderRepositoryAdapter}.
 */
public interface OrderJpaRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    /**
     * Fetches an order with its lines in one query.
     *
     * <p>Safe to use an entity graph here because a single order is loaded. The same graph on a
     * paginated query would make Hibernate fetch every row and paginate in memory.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);
}
