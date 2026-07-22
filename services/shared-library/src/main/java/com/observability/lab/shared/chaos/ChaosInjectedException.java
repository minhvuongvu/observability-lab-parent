package com.observability.lab.shared.chaos;

import com.observability.lab.shared.exception.TechnicalException;
import java.io.Serial;

/**
 * Thrown by a deliberately injected fault.
 *
 * <p>A distinct type rather than a bare {@link RuntimeException}, for one reason: it is greppable.
 * During an experiment the logs, traces and error-rate panels fill with failures, and being able to
 * separate "the fault I injected" from "something else broke while I was injecting it" is the
 * difference between a finding and a wild goose chase.
 *
 * <p>It extends {@link TechnicalException} so it flows through the platform's existing error model
 * and surfaces as a normal {@code PLT-5000} in the API envelope. That is deliberate too: an injected
 * failure that looked different from a real one on the wire would not be testing the error path that
 * matters.
 */
public class ChaosInjectedException extends TechnicalException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChaosInjectedException(String message) {
        super(message);
    }
}
