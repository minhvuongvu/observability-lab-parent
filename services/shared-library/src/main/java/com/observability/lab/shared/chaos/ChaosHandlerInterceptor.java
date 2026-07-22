package com.observability.lab.shared.chaos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Throws the injected exception, from inside the {@code DispatcherServlet}.
 *
 * <p>An interceptor rather than a filter, and the distinction is not cosmetic. A filter runs outside
 * the dispatcher, so anything it throws unwinds past {@code @RestControllerAdvice} and is rendered by
 * the servlet container's default error page — {@code {"timestamp":...,"error":"Internal Server
 * Error"}}, with no {@code ApiResponse} envelope and, crucially, no {@code meta.traceId}.
 *
 * <p>That would make an injected failure look different on the wire from a real one, which defeats
 * the purpose. Every genuine failure in this platform carries the envelope and the trace id — that
 * is what lets somebody paste a trace id from a failed response into Tempo — so a simulated failure
 * that does not is simulating the wrong thing, and the first scenario to use it would "discover"
 * that error responses lack correlation ids.
 *
 * <p>Latency stays in {@link ChaosRequestFilter} for the opposite reason: it should apply to every
 * request including the ones about to be rejected by authentication, because in a real overload the
 * security filter is queued behind the same exhausted thread pool as everything else.
 */
public class ChaosHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChaosHandlerInterceptor.class);

    private final ChaosRegistry registry;

    public ChaosHandlerInterceptor(ChaosRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler) {

        String path = request.getRequestURI();
        // The switch must never sit behind the fault, and a health check that
        // fails restarts the container and destroys the experiment.
        if (path.startsWith("/api/v1/chaos") || path.startsWith("/actuator")) {
            return true;
        }

        if (registry.shouldApply(ChaosRequestFilter.EXCEPTION)) {
            String message = registry.find(ChaosRequestFilter.EXCEPTION)
                    .map(t -> String.valueOf(t.attributes().getOrDefault(
                            "message", "chaos: deliberate failure injected for observability learning")))
                    .orElse("chaos: deliberate failure injected for observability learning");
            log.warn("Chaos exception injected: path={}", path);
            throw new ChaosInjectedException(message);
        }
        return true;
    }
}
