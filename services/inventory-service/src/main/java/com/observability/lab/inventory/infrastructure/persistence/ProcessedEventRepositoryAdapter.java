package com.observability.lab.inventory.infrastructure.persistence;

import com.observability.lab.inventory.application.ProcessedEventRepository;
import com.observability.lab.inventory.application.ReservationResult;
import com.observability.lab.inventory.domain.ProcessedEvent;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Records which events have already been applied, and what they decided.
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
    public Optional<RecordedOutcome> outcomeOf(String eventId) {
        return jpa.findById(eventId)
                // Null for rows written before outcomes were recorded. Mapping that to an empty
                // result rather than guessing a decision keeps "I do not know" distinguishable from
                // "it was rejected".
                .filter(event -> event.getOutcome() != null)
                .map(event -> new RecordedOutcome(
                        ReservationResult.Outcome.valueOf(event.getOutcome()),
                        event.getOutcomeDetail()));
    }

    @Override
    public void markProcessed(String eventId, String eventType, RecordedOutcome outcome) {
        jpa.save(new ProcessedEvent(eventId, eventType,
                outcome == null ? null : outcome.outcome().name(),
                outcome == null ? null : outcome.detail()));
    }
}
