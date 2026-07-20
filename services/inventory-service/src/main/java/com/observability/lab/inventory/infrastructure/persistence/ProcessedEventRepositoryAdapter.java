package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.application.ProcessedEventRepository;
import com.observability.lab.inventory.domain.ProcessedEvent;
import org.springframework.stereotype.Repository;

/**
 * Records which events have already been applied.
 *
 * <p>Both the check and the write happen inside the caller's transaction, which is what makes the
 * de-duplication sound: the marker and the effect it describes commit together or not at all.
 */
@Repository
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpa;

    public ProcessedEventRepositoryAdapter(ProcessedEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean hasProcessed(String eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public void markProcessed(String eventId, String eventType) {
        jpa.save(new ProcessedEvent(eventId, eventType));
    }
}
