package com.observability.lab.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BaseEntity identity semantics")
class BaseEntityTest {

    private static final class Order extends BaseEntity<Long> {
        private Long id;

        private Order(Long id) {
            this.id = id;
        }

        /** Stands in for the identifier Hibernate assigns at flush time. */
        void assignId(Long assigned) {
            this.id = assigned;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    private static final class Invoice extends BaseEntity<Long> {
        private final Long id;

        private Invoice(Long id) {
            this.id = id;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    @Test
    @DisplayName("two instances of one type with the same id are the same row")
    void sameIdSameType() {
        assertThat(new Order(1L)).isEqualTo(new Order(1L));
    }

    @Test
    @DisplayName("different ids are different rows")
    void differentIds() {
        assertThat(new Order(1L)).isNotEqualTo(new Order(2L));
    }

    @Test
    @DisplayName("the same id in a different table is a different row")
    void sameIdDifferentType() {
        assertThat((Object) new Order(1L)).isNotEqualTo(new Invoice(1L));
    }

    @Test
    @DisplayName("an unsaved entity is equal only to itself")
    void unsavedEntitiesAreDistinct() {
        Order first = new Order(null);
        Order second = new Order(null);

        // Two objects that carry identical values are still two rows waiting to be written.
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo(first);
    }

    @Test
    @DisplayName("stays findable in a HashSet after being assigned an id")
    void survivesIdentifierAssignment() {
        // The reason hashCode is a per-type constant rather than derived from the id. Hashing the
        // id would move the entity to a different bucket the moment it is flushed, and the instance
        // already inside this set could never be found again.
        Order order = new Order(null);
        Set<Order> set = new HashSet<>();
        set.add(order);

        order.assignId(42L);

        assertThat(set).contains(order);
        assertThat(set.iterator().next()).isSameAs(order);
    }

    @Test
    @DisplayName("reports id and version in toString for log output")
    void readableToString() {
        assertThat(new Order(7L).toString()).contains("Order", "id=7");
    }
}
