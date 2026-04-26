package com.sweta.limitorder.simulator.api;

import java.util.UUID;

/**
 * Carries everything the simulator needs after a successful login: the
 * raw JWT for outgoing {@code Authorization: Bearer …} headers and the
 * userId / display name picked up from the {@code /api/auth/login}
 * response body.
 *
 * <p>Held by {@link TokenCache} for the duration of a run. We don't
 * decode the JWT here — the backend already verifies it and the
 * simulator only needs the opaque string.
 */
public record JwtToken(String token, UUID userId, String name) {}
