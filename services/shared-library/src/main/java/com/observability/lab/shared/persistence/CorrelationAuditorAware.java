package com.observability.lab.shared.persistence;

import com.observability.lab.shared.correlation.CorrelationContext;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;

/**
 * Supplies the actor recorded in {@code created_by} and {@code updated_by}.
 *
 * <p>Reads the authenticated subject from the correlation context, which the inbound filter
 * populates. That keeps auditing consistent with the logs: the value in {@code created_by} is the
 * same string that appears as {@code user_id} on the log lines for the request that wrote the row.
 */
public class CorrelationAuditorAware implements AuditorAware<String> {

    /**
     * Actor recorded when there is no authenticated user.
     *
     * <p>Plenty of legitimate writes have no user behind them: a Kafka consumer reacting to an
     * event, a scheduled job. Recording a name for them is better than leaving the column null,
     * because "written by the system" and "we forgot to record who wrote this" are different facts
     * and a null cannot tell them apart.
     */
    static final String SYSTEM_ACTOR = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.of(CorrelationContext.userId().orElse(SYSTEM_ACTOR));
    }
}
