package com.sweta.limitorder.ws;

import com.sweta.limitorder.auth.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Captures the authenticated user from {@link SecurityContextHolder} (which
 * the {@link com.sweta.limitorder.auth.JwtAuthFilter} populates on the
 * upgrade HTTP request) and stashes it in the WebSocketSession attributes —
 * the security context isn't available from inside message handlers, so we
 * snapshot the principal at handshake time.
 *
 * <p>Defense-in-depth: if for any reason no authenticated principal is
 * present at handshake time, we reject with 401 instead of opening an
 * unauthenticated socket.
 */
@Component
@Slf4j
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USER_NAME = "userName";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("ws handshake rejected — no authenticated principal");
            return false;
        }
        attributes.put(ATTR_USER_ID, principal.userId());
        attributes.put(ATTR_USER_NAME, principal.name());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
