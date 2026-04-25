package com.sweta.limitorder.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the {@code Authorization: Bearer <jwt>} header on every request
 * and populates the security context with the token's userId as principal.
 *
 * <p>Falls back to the {@code token} query parameter so the WebSocket handshake
 * can authenticate (browser {@code WebSocket} can't set headers — see
 * architecture §4.9).
 *
 * <p>Invalid / expired / missing tokens leave the security context empty;
 * Spring Security's {@code AuthorizationFilter} downstream returns 401 for
 * any non-permitted path.
 *
 * <p>Not annotated as {@code @Component} on purpose: registered explicitly via
 * {@link com.sweta.limitorder.config.SecurityConfig} so it isn't double-wired
 * into both the servlet and security filter chains.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String QUERY_PARAM_FALLBACK = "token";

    private final JwtService jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                AuthenticatedUser principal = jwt.verify(token);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("userId", principal.userId().toString());
            } catch (JwtException ex) {
                log.debug("rejected JWT: {}", ex.getMessage());
                // leave SecurityContext empty — downstream AuthorizationFilter returns 401
            }
        }
        chain.doFilter(request, response);
        // MDC.userId is cleared by MdcEnrichmentFilter's finally block, which
        // runs in the outer filter scope.
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            if (!value.isEmpty()) return value;
        }
        String fromQuery = request.getParameter(QUERY_PARAM_FALLBACK);
        return (fromQuery != null && !fromQuery.isBlank()) ? fromQuery : null;
    }
}
