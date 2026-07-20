package com.observability.lab.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An entity that records who changed it and when.
 *
 * <p>The four columns are populated by Spring Data's auditing listener, so no service writes them by
 * hand and none can forget to. That matters more than the convenience: an audit trail maintained by
 * application code is an audit trail with gaps wherever somebody took a shortcut.
 *
 * <p>Timestamps are {@link Instant}, so they carry an unambiguous point in time rather than a local
 * one. Two services in different regions writing local timestamps into the same log store produce
 * an ordering that is quietly wrong.
 *
 * <p>Requires {@code @EnableJpaAuditing} on the consuming service, and an {@code AuditorAware} bean
 * to supply the actor — the shared library contributes {@link CorrelationAuditorAware} for that.
 *
 * @param <ID> identifier type
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity<ID> extends BaseEntity<ID> {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
