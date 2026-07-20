package com.observability.lab.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("CorrelationFilter")
class CorrelationFilterTest {

    private static final String VALID_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final CorrelationFilter filter =
            new CorrelationFilter(new ServiceIdentity("order-service", "1.2.3", "test"));

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Runs the filter and returns the correlation fields as they were seen inside the chain. */
    private Map<String, String> runFilter() throws Exception {
        Map<String, String> insideChain = new HashMap<>();
        FilterChain chain = (req, res) -> insideChain.putAll(CorrelationContext.snapshot());
        filter.doFilter(request, response, chain);
        return insideChain;
    }

    @Nested
    @DisplayName("identifier handling")
    class Identifiers {

        @Test
        @DisplayName("generates a request id when the caller supplies none")
        void generatesRequestId() throws Exception {
            Map<String, String> fields = runFilter();
            assertThat(fields.get(CorrelationFields.REQUEST_ID)).isNotBlank();
        }

        @Test
        @DisplayName("keeps a well-formed request id supplied by the caller")
        void keepsSuppliedRequestId() throws Exception {
            request.addHeader(CorrelationHeaders.REQUEST_ID, "abc-123_XYZ.9");
            assertThat(runFilter().get(CorrelationFields.REQUEST_ID)).isEqualTo("abc-123_XYZ.9");
        }

        @Test
        @DisplayName("adopts the trace and parent span from a valid traceparent")
        void adoptsTraceParent() throws Exception {
            request.addHeader(CorrelationHeaders.TRACEPARENT, VALID_TRACEPARENT);
            Map<String, String> fields = runFilter();
            assertThat(fields.get(CorrelationFields.TRACE_ID))
                    .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(fields.get(CorrelationFields.SPAN_ID)).isEqualTo("00f067aa0ba902b7");
        }

        @Test
        @DisplayName("falls back to the trace id for correlation when the caller supplies none")
        void correlationFallsBackToTraceId() throws Exception {
            request.addHeader(CorrelationHeaders.TRACEPARENT, VALID_TRACEPARENT);
            assertThat(runFilter().get(CorrelationFields.CORRELATION_ID))
                    .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }

        @Test
        @DisplayName("falls back to the request id when there is no trace either")
        void correlationFallsBackToRequestId() throws Exception {
            Map<String, String> fields = runFilter();
            assertThat(fields.get(CorrelationFields.CORRELATION_ID))
                    .isEqualTo(fields.get(CorrelationFields.REQUEST_ID));
        }

        @Test
        @DisplayName("preserves a caller-supplied correlation id over the trace id")
        void callerCorrelationWins() throws Exception {
            request.addHeader(CorrelationHeaders.TRACEPARENT, VALID_TRACEPARENT);
            request.addHeader(CorrelationHeaders.CORRELATION_ID, "checkout-42");
            assertThat(runFilter().get(CorrelationFields.CORRELATION_ID)).isEqualTo("checkout-42");
        }
    }

    @Nested
    @DisplayName("hostile input")
    class HostileInput {

        @ParameterizedTest(name = "[{index}] rejects {0}")
        @ValueSource(strings = {
            // A newline lets a caller forge whole log entries.
            "abc\ndef",
            "abc\r\nWARN forged entry",
            // Quotes and braces break the record a JSON pipeline is assembling.
            "abc\"def",
            "{\"injected\":true}",
            " ",
        })
        @DisplayName("a request id that is unsafe to write into a log line")
        void rejectsUnsafeRequestId(String hostile) throws Exception {
            request.addHeader(CorrelationHeaders.REQUEST_ID, hostile);
            String actual = runFilter().get(CorrelationFields.REQUEST_ID);
            assertThat(actual).isNotEqualTo(hostile).isNotBlank().doesNotContain("\n", "\r", "\"");
        }

        @Test
        @DisplayName("an oversized request id, which would amplify into every log line")
        void rejectsOversizedRequestId() throws Exception {
            request.addHeader(CorrelationHeaders.REQUEST_ID, "a".repeat(129));
            assertThat(runFilter().get(CorrelationFields.REQUEST_ID)).hasSizeLessThan(129);
        }

        @Test
        @DisplayName("a malformed traceparent, starting a fresh trace instead of failing")
        void ignoresMalformedTraceParent() throws Exception {
            request.addHeader(CorrelationHeaders.TRACEPARENT, "totally-invalid");
            Map<String, String> fields = runFilter();
            assertThat(fields).doesNotContainKey(CorrelationFields.TRACE_ID);
            assertThat(fields.get(CorrelationFields.REQUEST_ID)).isNotBlank();
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("echoes the identifiers back to the caller")
        void echoesHeaders() throws Exception {
            Map<String, String> fields = runFilter();
            assertThat(response.getHeader(CorrelationHeaders.REQUEST_ID))
                    .isEqualTo(fields.get(CorrelationFields.REQUEST_ID));
            assertThat(response.getHeader(CorrelationHeaders.CORRELATION_ID))
                    .isEqualTo(fields.get(CorrelationFields.CORRELATION_ID));
        }

        @Test
        @DisplayName("stamps the service identity onto the context")
        void stampsServiceIdentity() throws Exception {
            Map<String, String> fields = runFilter();
            assertThat(fields.get(CorrelationFields.SERVICE)).isEqualTo("order-service");
            assertThat(fields.get(CorrelationFields.VERSION)).isEqualTo("1.2.3");
            assertThat(fields.get(CorrelationFields.ENVIRONMENT)).isEqualTo("test");
        }

        @Test
        @DisplayName("clears the context afterwards so a pooled thread carries nothing over")
        void clearsContextAfterwards() throws Exception {
            runFilter();
            assertThat(CorrelationContext.snapshot()).isEmpty();
        }

        @Test
        @DisplayName("clears the context even when the chain throws")
        void clearsContextOnFailure() {
            FilterChain exploding = (req, res) -> {
                throw new IllegalStateException("downstream blew up");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                    .isInstanceOf(IllegalStateException.class);

            // The important assertion. A leak here silently attributes the next request on this
            // thread to the previous request's user.
            assertThat(CorrelationContext.snapshot()).isEmpty();
        }
    }
}
