package com.observability.lab.shared.security;

import com.observability.lab.shared.correlation.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps the authenticated subject onto the correlation context, so every log line a request emits
 * after authentication carries the real user rather than nothing.
 *
 * <p>Registered inside the security filter chain, immediately after the bearer token has been
 * validated: by then {@link SecurityContextHolder} holds the authenticated principal, and its name
 * is the token's {@code preferred_username} (the resource server is configured to use that claim as
 * the principal name). This is the point the header-based value set earlier by the correlation
 * filter is meant to be superseded by — the security context is authoritative, a header is not.
 *
 * <p>It only writes; it never clears. The correlation filter wraps the entire chain and clears the
 * whole context in its {@code finally}, so the user id is torn down with everything else when the
 * pooled request thread is returned. Clearing here would blank the field for the rest of the
 * request.
 */
public class SecurityUserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Only a real token counts. Permitted endpoints (actuator, docs) run as an
        // AnonymousAuthenticationToken whose name is "anonymousUser"; stamping that as the subject
        // would be worse than leaving the field empty.
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication) {
            CorrelationContext.userId(authentication.getName());
        }
        chain.doFilter(request, response);
    }
}
