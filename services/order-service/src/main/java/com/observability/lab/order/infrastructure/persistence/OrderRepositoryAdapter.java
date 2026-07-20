package com.observability.lab.order.infrastructure.persistence;

import com.observability.lab.order.application.OrderRepository;
import com.observability.lab.order.domain.Order;
import com.observability.lab.order.domain.OrderErrorCode;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.exception.BusinessException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * Adapts Spring Data to the {@code OrderRepository} port.
 *
 * <p>Thin by design, with one real responsibility beyond delegation: translating persistence
 * failures into the platform's exception model. A {@code DataIntegrityViolationException} escaping
 * to the controller would be reported as an unexpected internal error and logged with a stack trace,
 * when in fact it is a perfectly ordinary conflict that the caller can act on.
 */
@Repository
public class OrderRepositoryAdapter implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public OrderRepositoryAdapter(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Order save(Order order) {
        try {
            return jpaRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException exception) {
            // Flushing here rather than at transaction commit is what makes this catchable at all:
            // a violation surfacing during commit is outside any application try/catch.
            throw new BusinessException(OrderErrorCode.DUPLICATE_ORDER_NUMBER,
                    "Order '" + order.getOrderNumber() + "' already exists.", exception);
        }
    }

    @Override
    public Optional<Order> findByOrderNumber(String orderNumber) {
        return jpaRepository.findByOrderNumber(orderNumber);
    }

    @Override
    public boolean existsByOrderNumber(String orderNumber) {
        return jpaRepository.existsByOrderNumber(orderNumber);
    }

    @Override
    public Page<Order> search(String customerId, OrderStatus status, Pageable pageable) {
        return jpaRepository.findAll(matching(customerId, status), pageable);
    }

    @Override
    public void delete(Order order) {
        jpaRepository.delete(order);
    }

    /**
     * Builds the filter, skipping any criterion that was not supplied.
     *
     * <p>A specification rather than a query with {@code (:param is null or ...)} conditions: those
     * defeat index usage, and PostgreSQL cannot always infer the type of a null enum parameter.
     */
    private static Specification<Order> matching(String customerId, OrderStatus status) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(2);
            if (customerId != null && !customerId.isBlank()) {
                predicates.add(builder.equal(root.get("customerId"), customerId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
