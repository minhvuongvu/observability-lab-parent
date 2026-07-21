package com.observability.lab.shared.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.observability.lab.shared.api.FieldViolation;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.ErrorCategory;
import com.observability.lab.shared.exception.ErrorCode;
import com.observability.lab.shared.exception.ForbiddenException;
import com.observability.lab.shared.exception.IntegrationException;
import com.observability.lab.shared.exception.ResourceNotFoundException;
import com.observability.lab.shared.exception.TechnicalException;
import com.observability.lab.shared.exception.UnauthorizedException;
import com.observability.lab.shared.exception.ValidationException;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.event.Level;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("GrpcStatusMapper")
class GrpcStatusMapperTest {

    @Nested
    @DisplayName("platform exceptions")
    class PlatformExceptions {

        @Test
        @DisplayName("a malformed request is INVALID_ARGUMENT")
        void validation() {
            assertThat(code(new ValidationException("bad"))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("a business refusal is FAILED_PRECONDITION, not an error")
        void businessRule() {
            // The distinction the whole taxonomy turns on: the request is well formed, and the
            // current state forbids it. INVALID_ARGUMENT would tell the caller to fix the request,
            // which would be wrong advice.
            assertThat(code(new BusinessException(code("INV-4220", ErrorCategory.BUSINESS_RULE), "no stock")))
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("a conflict is ALREADY_EXISTS")
        void conflict() {
            assertThat(code(new BusinessException(code("INV-4090", ErrorCategory.CONFLICT), "dupe")))
                    .isEqualTo(Status.Code.ALREADY_EXISTS);
        }

        @Test
        @DisplayName("an unknown entity is NOT_FOUND")
        void notFound() {
            assertThat(code(new ResourceNotFoundException("gone"))).isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        @DisplayName("missing credentials are UNAUTHENTICATED and insufficient ones PERMISSION_DENIED")
        void authentication() {
            assertThat(code(new UnauthorizedException("no token"))).isEqualTo(Status.Code.UNAUTHENTICATED);
            assertThat(code(new ForbiddenException("not you"))).isEqualTo(Status.Code.PERMISSION_DENIED);
        }

        @Test
        @DisplayName("a failing dependency is UNAVAILABLE, so it can be retried elsewhere")
        void integration() {
            assertThat(code(IntegrationException.failed("oracle", "refused", null)))
                    .isEqualTo(Status.Code.UNAVAILABLE);
        }

        @Test
        @DisplayName("a dependency timeout is DEADLINE_EXCEEDED, not UNAVAILABLE")
        void timeout() {
            // Both mean "no answer"; the difference is whose budget ran out. Blurring them produces
            // a retry storm against an already-slow service.
            assertThat(code(IntegrationException.timedOut("oracle", "slow", null)))
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        }

        @Test
        @DisplayName("an unexpected fault is INTERNAL")
        void technical() {
            assertThat(code(new TechnicalException("boom"))).isEqualTo(Status.Code.INTERNAL);
        }
    }

    @Nested
    @DisplayName("data-access failures")
    class DataAccess {

        @Test
        @DisplayName("a query timeout is UNAVAILABLE, never INTERNAL")
        void queryTimeout() {
            // The example the taxonomy exists for. Reported as INTERNAL it is not retried, the
            // request fails, and the alert points at this service as if it had a bug.
            assertThat(code(new QueryTimeoutException("slow"))).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(code(new CannotAcquireLockException("busy"))).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(code(new DataAccessResourceFailureException("down")))
                    .isEqualTo(Status.Code.UNAVAILABLE);
        }

        @Test
        @DisplayName("an optimistic lock conflict is ABORTED, which is retryable")
        void optimisticLock() {
            assertThat(code(new OptimisticLockingFailureException("stale")))
                    .isEqualTo(Status.Code.ABORTED);
        }
    }

    @Nested
    @DisplayName("everything else")
    class Fallbacks {

        @Test
        @DisplayName("a status that was already chosen is respected")
        void passesThroughAnExistingStatus() {
            Status chosen = Status.DEADLINE_EXCEEDED.withDescription("insufficient remaining deadline");
            assertThat(GrpcStatusMapper.toStatus(chosen.asRuntimeException()).getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        }

        @Test
        @DisplayName("a rejected task is RESOURCE_EXHAUSTED, so backoff applies rather than failure")
        void rejectedExecution() {
            assertThat(code(new RejectedExecutionException())).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        }

        @Test
        @DisplayName("a timeout is DEADLINE_EXCEEDED")
        void javaTimeout() {
            assertThat(code(new TimeoutException())).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        }

        @Test
        @DisplayName("anything unmapped is INTERNAL and leaks nothing")
        void unmapped() {
            Status status = GrpcStatusMapper.toStatus(
                    new IllegalStateException("SELECT * FROM stock_levels WHERE ..."));

            assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
            // The SQL is in the logs, behind access control - never in the description a client sees.
            assertThat(status.getDescription()).isEqualTo("internal error");
        }
    }

    @Nested
    @DisplayName("error details")
    class Details {

        @Test
        @DisplayName("field violations travel as structured detail, not as prose")
        void carriesBadRequest() {
            var trailers = GrpcStatusMapper.toTrailers(new ValidationException("nope", List.of(
                    new FieldViolation("items[3].product_sku", "must not be blank"))));

            assertThat(trailers.keys()).contains("google.rpc.status-bin");
        }

        @Test
        @DisplayName("a failure with nothing structured to say carries empty trailers")
        void emptyWhenNothingToSay() {
            assertThat(GrpcStatusMapper.toTrailers(new TechnicalException("boom")).keys()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fault classification")
    class Faults {

        @Test
        @DisplayName("only genuine faults count, so the error rate tracks health not catalogue quality")
        void businessOutcomesAreNotFaults() {
            assertThat(GrpcStatusMapper.isFault(Status.Code.INTERNAL)).isTrue();
            assertThat(GrpcStatusMapper.isFault(Status.Code.UNAVAILABLE)).isTrue();
            assertThat(GrpcStatusMapper.isFault(Status.Code.DATA_LOSS)).isTrue();

            assertThat(GrpcStatusMapper.isFault(Status.Code.NOT_FOUND)).isFalse();
            assertThat(GrpcStatusMapper.isFault(Status.Code.FAILED_PRECONDITION)).isFalse();
            assertThat(GrpcStatusMapper.isFault(Status.Code.INVALID_ARGUMENT)).isFalse();
        }

        @Test
        @DisplayName("business answers log at INFO and only bugs log at ERROR")
        void logLevels() {
            assertThat(GrpcStatusMapper.levelFor(Status.Code.OK)).isEqualTo(Level.INFO);
            assertThat(GrpcStatusMapper.levelFor(Status.Code.NOT_FOUND)).isEqualTo(Level.INFO);
            assertThat(GrpcStatusMapper.levelFor(Status.Code.FAILED_PRECONDITION)).isEqualTo(Level.INFO);

            assertThat(GrpcStatusMapper.levelFor(Status.Code.INVALID_ARGUMENT)).isEqualTo(Level.WARN);
            assertThat(GrpcStatusMapper.levelFor(Status.Code.DEADLINE_EXCEEDED)).isEqualTo(Level.WARN);

            assertThat(GrpcStatusMapper.levelFor(Status.Code.INTERNAL)).isEqualTo(Level.ERROR);
            assertThat(GrpcStatusMapper.levelFor(Status.Code.UNKNOWN)).isEqualTo(Level.ERROR);
        }

        @ParameterizedTest
        @EnumSource(Status.Code.class)
        @DisplayName("every status has a level, so a new one cannot go unclassified")
        void everyCodeIsClassified(Status.Code code) {
            assertThat(GrpcStatusMapper.levelFor(code)).isNotNull();
        }
    }

    private static Status.Code code(Throwable failure) {
        return GrpcStatusMapper.toStatus(failure).getCode();
    }

    private static ErrorCode code(String value, ErrorCategory category) {
        return new ErrorCode() {
            @Override
            public String code() {
                return value;
            }

            @Override
            public ErrorCategory category() {
                return category;
            }

            @Override
            public String defaultMessage() {
                return "test";
            }
        };
    }
}
