package com.observability.lab.shared.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies injected latency, before anything else in the chain gets a turn.
 *
 * <p>A filter rather than an endpoint, because "this service is slow" is not a property of one
 * handler. A latency endpoint that is slow only when you call it demonstrates nothing; the interesting
 * version makes the whole API slow, so the dashboards, the alerts, the gateway's timeouts and the
 * callers' circuit breakers all see what they would see in a real degradation.
 *
 * <p>Latency belongs out here, ahead of security, because in a real overload the authentication
 * filter is queued behind the same exhausted thread pool as everything else. The exception fault
 * deliberately does not — see {@link ChaosHandlerInterceptor} for why it has to run inside the
 * dispatcher instead.
 *
 * <h2>Two paths are deliberately exempt</h2>
 *
 * <ul>
 *   <li>{@code /api/v1/chaos/**} — otherwise a 30-second latency toggle makes the endpoint that
 *       switches it off take 30 seconds too, and a 100% exception toggle makes the system
 *       unrecoverable without a restart. The switch must never be behind the fault.
 *   <li>{@code /actuator/**} — the container healthcheck and Kong's upstream probe both read
 *       readiness. Failing those turns a latency experiment into a container restart, which
 *       destroys the very state being observed. Making the <em>service</em> slow while it still
 *       reports healthy is also the more instructive failure: it is what a real overload looks
 *       like, and it is why latency alerts exist when health checks are green.
 * </ul>
 */
public class ChaosRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChaosRequestFilter.class);

    public static final String LATENCY = "latency";
    public static final String EXCEPTION = "exception";

    private final ChaosRegistry registry;

    public ChaosRequestFilter(ChaosRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/chaos") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // The exception fault is NOT applied here. It lives in
        // ChaosHandlerInterceptor, inside the DispatcherServlet, so that
        // @RestControllerAdvice renders it in the platform's API envelope with a
        // trace id - exactly as a real failure is rendered. Thrown from a filter
        // it would bypass the advice and produce the container's default error
        // page instead, which is a different shape from every genuine error this
        // platform returns.
        if (registry.shouldApply(LATENCY)) {
            long delayMs = registry.find(LATENCY)
                    .map(t -> ((Number) t.attributes().getOrDefault("delayMs", 0)).longValue())
                    .orElse(0L);
            if (delayMs > 0) {
                try {
                    // Thread.sleep, not a non-blocking delay, and that is the fault being modelled:
                    // the request thread is held for the duration. On a servlet stack that is what
                    // makes tomcat_threads_busy climb and eventually exhaust, which is the symptom
                    // worth watching.
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ServletException("interrupted while injecting chaos latency", e);
                }
            }
        }

        chain.doFilter(request, response);
    }
}
