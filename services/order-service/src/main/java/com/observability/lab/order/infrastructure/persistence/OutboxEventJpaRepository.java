package com.observability.lab.order.infrastructure.persistence;

import com.observability.lab.order.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Spring Data access to the outbox. */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of undelivered events for this relay run.
     *
     * <p>{@code PESSIMISTIC_WRITE} combined with the {@code -2} lock timeout — Hibernate's spelling
     * of {@code SKIP LOCKED} — is what makes the relay safe to run in more than one instance. Without
     * it, two relays select the same rows and every event is published twice; with a plain lock and
     * no skip, the second relay blocks behind the first instead of working on different rows.
     *
     * <p>Ordered by {@code occurredAt} so the backlog drains oldest-first and per-key ordering on the
     * topic matches the order things actually happened.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.occurredAt asc")
    List<OutboxEvent> claimUnpublished(Pageable batch);

    /** Backlog depth. Read by the relay's own logging and, later, by a gauge. */
    @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
    long countUnpublished();

    /**
     * Discards delivered events older than the cutoff.
     *
     * <p>Published rows are history, not queue: keeping them forever turns an append-only table into
     * the largest one in the database for no operational benefit. They are retained long enough to
     * answer "was this event actually sent", then removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
