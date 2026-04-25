package com.sweta.limitorder.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the authenticated user's identity. Useful for the frontend to
 * verify a stored JWT is still valid on app boot, and for the Phase 4
 * integration tests to have a real protected endpoint to hit.
 *
 * <p>Reads from the security context populated by {@link JwtAuthFilter} —
 * never hits the database.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new MeResponse(principal.userId(), principal.name());
    }
}
