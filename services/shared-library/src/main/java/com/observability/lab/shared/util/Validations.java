package com.observability.lab.shared.util;

import com.observability.lab.shared.api.FieldViolation;
import com.observability.lab.shared.exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Runs Bean Validation on demand, outside the request-binding path.
 *
 * <p>Spring validates {@code @Valid} controller arguments automatically. It does not validate an
 * object the application layer builds itself — a command assembled from several sources, an entity
 * about to be persisted — and those are exactly the objects whose invariants matter most.
 *
 * <p>Static rather than injected on purpose: the application and domain layers are kept free of
 * framework types, and a validation call that requires a constructor parameter is a call that gets
 * skipped.
 */
public final class Validations {

    /**
     * Lazy, thread-safe singleton via class-initialisation semantics.
     *
     * <p>Building a factory costs tens of milliseconds and it is immutable and reusable afterwards.
     * It is intentionally never closed: it lives as long as the JVM does, and the alternative is a
     * factory rebuilt on every validation call.
     */
    private static final class ValidatorHolder {
        private static final Validator INSTANCE;

        static {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                INSTANCE = factory.getValidator();
            }
        }
    }

    private Validations() {
        throw new AssertionError("No instances.");
    }

    /**
     * Validates a bean and throws when it is invalid.
     *
     * @param bean   object to validate
     * @param groups optional validation groups
     * @throws ValidationException when any constraint is violated
     */
    public static <T> void validate(T bean, Class<?>... groups) {
        List<FieldViolation> violations = check(bean, groups);
        if (!violations.isEmpty()) {
            throw new ValidationException(
                    "Validation failed for " + bean.getClass().getSimpleName() + ".", violations);
        }
    }

    /**
     * Validates a bean and reports the failures instead of throwing.
     *
     * <p>Useful when several objects are checked and the caller wants every problem at once rather
     * than only the first.
     *
     * @return the violations, sorted by field so output is stable and diffable; empty when valid
     */
    public static <T> List<FieldViolation> check(T bean, Class<?>... groups) {
        if (bean == null) {
            return List.of(new FieldViolation("", "The object to validate must not be null."));
        }
        Set<ConstraintViolation<T>> violations = ValidatorHolder.INSTANCE.validate(bean, groups);
        return violations.stream()
                .map(violation ->
                        new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                // Bean Validation returns an unordered Set, so without this the same invalid object
                // produces a different message order run to run, which makes assertions flaky.
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
    }
}
