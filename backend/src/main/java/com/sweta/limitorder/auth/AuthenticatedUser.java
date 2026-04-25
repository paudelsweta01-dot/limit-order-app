package com.sweta.limitorder.auth;

import java.util.UUID;

/**
 * The principal stored in {@link org.springframework.security.core.SecurityContext}
 * after a successful JWT verification. Carries the two claims that the rest of
 * the app cares about — the userId and the user's display name — so we never
 * need to re-hit the database to know who the caller is.
 */
public record AuthenticatedUser(UUID userId, String name) {
}
