package com.observability.lab.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.FieldViolation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** An error code owned by a bounded context, as a real service would declare. */
    private enum TestErrorCode implements ErrorCode {
        INSUFFICIENT_STOCK("ORD-4221", ErrorCategory.BUSINESS_RULE, "Not enough stock."),
        DUPLICATE_ORDER("ORD-4090", ErrorCategory.CONFLICT, "That order already exists.");

        private final String code;
        private final ErrorCategory category;
        private final String defaultMessage;

        TestErrorCode(String code, ErrorCategory category, String defaultMessage) {
            this.code = code;
            this.category = category;
            this.defaultMessage = defaultMessage;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public ErrorCategory category() {
            return category;
        }

        @Override
        public String defaultMessage() {
            return defaultMessage;
        }
    }

    @Nested
    @DisplayName("status mapping")
    class StatusMapping {

        @ParameterizedTest(name = "{0} maps to a status")
        @EnumSource(ErrorCategory.class)
        @DisplayName("covers every category, so a new one cannot be forgotten")
        void everyCategoryMaps(ErrorCategory category) {
            assertThat(ErrorResponseFactory.statusFor(category)).isNotNull();
        }

        @Test
        @DisplayName("distinguishes a refused rule (422) from a state conflict (409)")
        void businessRuleVersusConflict() {
            assertThat(ErrorResponseFactory.statusFor(ErrorCategory.BUSINESS_RULE))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(ErrorResponseFactory.statusFor(ErrorCategory.CONFLICT))
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("distinguishes a failing dependency (502) from a slow one (504)")
        void integrationVersusTimeout() {
            assertThat(ErrorResponseFactory.statusFor(ErrorCategory.INTEGRATION))
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(ErrorResponseFactory.statusFor(ErrorCategory.TIMEOUT))
                    .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        }
    }

    @Nested
    @DisplayName("log level policy")
    class LogLevels {

        @Test
        @DisplayName("treats an enforced business rule as INFO, not as an error")
        void businessRuleIsNotAnError() {
            // An alert built on error rate is worthless if "insufficient stock" inflates it.
            assertThat(ErrorCategory.BUSINESS_RULE.logLevel().toString()).isEqualTo("INFO");
            assertThat(ErrorCategory.BUSINESS_RULE.isServerFault()).isFalse();
        }

        @Test
        @DisplayName("treats a caller's mistake as WARN with no stack trace")
        void clientFaultsAreWarnings() {
            assertThat(ErrorCategory.VALIDATION.isServerFault()).isFalse();
            assertThat(ErrorCategory.NOT_FOUND.isServerFault()).isFalse();
            assertThat(ErrorCategory.AUTHENTICATION.isServerFault()).isFalse();
        }

        @Test
        @DisplayName("treats only our own failures as server faults")
        void serverFaults() {
            assertThat(ErrorCategory.TECHNICAL.isServerFault()).isTrue();
            assertThat(ErrorCategory.INTEGRATION.isServerFault()).isTrue();
            assertThat(ErrorCategory.TIMEOUT.isServerFault()).isTrue();
        }
    }

    @Nested
    @DisplayName("response body")
    class ResponseBody {

        @Test
        @DisplayName("carries the context-specific code and message for a business failure")
        void businessFailure() {
            ResponseEntity<ApiResponse<Void>> result = handler.handlePlatformException(
                    new BusinessException(TestErrorCode.INSUFFICIENT_STOCK, "Only 2 left in stock."));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            ApiResponse<Void> body = result.getBody();
            assertThat(body).isNotNull();
            assertThat(body.success()).isFalse();
            assertThat(body.data()).isNull();
            assertThat(body.error().code()).isEqualTo("ORD-4221");
            assertThat(body.error().message()).isEqualTo("Only 2 left in stock.");
        }

        @Test
        @DisplayName("passes field violations through for a validation failure")
        void validationFailure() {
            ResponseEntity<ApiResponse<Void>> result =
                    handler.handlePlatformException(new ValidationException(
                            "Invalid order.", List.of(new FieldViolation("quantity", "must be positive"))));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().error().violations())
                    .singleElement()
                    .satisfies(violation -> {
                        assertThat(violation.field()).isEqualTo("quantity");
                        assertThat(violation.message()).isEqualTo("must be positive");
                    });
        }

        @Test
        @DisplayName("never leaks an internal message on a server fault")
        void hidesInternalDetail() {
            String leaky = "jdbc:postgresql://db-prod-01.internal:5432/orderdb password=hunter2";

            ResponseEntity<ApiResponse<Void>> result =
                    handler.handlePlatformException(new TechnicalException(leaky));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(result.getBody().error().message())
                    .isEqualTo(PlatformErrorCode.INTERNAL_ERROR.defaultMessage())
                    .doesNotContain("jdbc", "password", "internal");
        }

        @Test
        @DisplayName("hides the detail of a failing dependency but keeps its status")
        void hidesIntegrationDetail() {
            ResponseEntity<ApiResponse<Void>> result = handler.handlePlatformException(
                    IntegrationException.timedOut("inventory-service", "read timed out after 5000ms", null));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(result.getBody().error().message())
                    .isEqualTo(PlatformErrorCode.UPSTREAM_TIMEOUT.defaultMessage())
                    .doesNotContain("inventory-service");
        }

        @Test
        @DisplayName("keeps the caller-facing message for a client fault, because it is actionable")
        void keepsClientFacingDetail() {
            ResponseEntity<ApiResponse<Void>> result = handler.handlePlatformException(
                    ResourceNotFoundException.of("Order", "42"));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody().error().message()).contains("Order", "42");
        }

        @Test
        @DisplayName("reports the catch-all as an internal error without echoing the cause")
        void catchAll() {
            ResponseEntity<ApiResponse<Void>> result =
                    handler.handleUnexpected(new IllegalStateException("connection pool exhausted"));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(result.getBody().error().code()).isEqualTo("PLT-5000");
            assertThat(result.getBody().error().message()).doesNotContain("connection pool");
        }
    }
}
