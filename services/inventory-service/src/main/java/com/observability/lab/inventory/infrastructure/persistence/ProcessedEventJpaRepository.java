package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the consumer de-duplication markers.
 *
 * <p>The identifier is the event id supplied by the producer, not a generated key: the whole point
 * is that the same event arriving twice collides on the same row.
 */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, String> {}
