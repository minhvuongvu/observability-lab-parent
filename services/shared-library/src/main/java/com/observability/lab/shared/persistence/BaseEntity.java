package com.observability.lab.shared.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.Hibernate;

/**
 * Root of every persistent entity: optimistic locking and correct identity semantics.
 *
 * <p>The identifier itself is left to the subclass. PostgreSQL and Oracle reach their best
 * behaviour through different generation strategies, and a base class that dictates one would force
 * a compromise on the other.
 *
 * <p>{@code @Version} is not optional. Two concurrent updates to the same row silently lose one
 * without it, and the loss is invisible — no exception, no log line, just a value that quietly
 * reverted. With it the second writer gets an {@code OptimisticLockException} and the conflict
 * becomes something the application can handle and the telemetry can count.
 *
 * @param <ID> identifier type
 */
@MappedSuperclass
public abstract class BaseEntity<ID> {

    @Version
    private Long version;

    /** @return the persistent identity, {@code null} until the entity is first flushed */
    public abstract ID getId();

    /** @return the optimistic lock version, {@code null} before the first flush */
    public Long getVersion() {
        return version;
    }

    /**
     * Two entities are the same when they are the same row.
     *
     * <p>An entity with no identifier yet is equal only to itself: two unsaved instances holding
     * identical field values are two distinct rows waiting to happen, not one.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        // Hibernate.getClass unwraps a lazy proxy. Plain getClass() would compare Order against
        // Order$HibernateProxy$xyz and declare two views of the same row unequal, which breaks
        // every collection the entity is put into.
        if (!Hibernate.getClass(this).equals(Hibernate.getClass(other))) {
            return false;
        }
        ID id = getId();
        return id != null && id.equals(((BaseEntity<?>) other).getId());
    }

    /**
     * Constant per entity type, deliberately.
     *
     * <p>Hashing the identifier looks more precise and is a bug: the id is null before the flush
     * and populated after it, so the hash of an instance already inside a {@code HashSet} changes
     * underneath it and the element can never be found again. A per-type constant costs a linear
     * scan within one type and is always correct.
     */
    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }

    @Override
    public String toString() {
        return Hibernate.getClass(this).getSimpleName() + "(id=" + getId() + ", version=" + version + ")";
    }
}
