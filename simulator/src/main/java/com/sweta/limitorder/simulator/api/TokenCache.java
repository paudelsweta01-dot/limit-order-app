package com.sweta.limitorder.simulator.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user JWT cache for the duration of a run (plan §2.2). Each
 * username keeps a single {@link JwtToken}; on 401 the caller invokes
 * {@link #invalidate(String)} and the next access re-logs in.
 *
 * <p>Concurrent map because the load-mode simulator (Phase 5) drives
 * multiple users in parallel and any of them can race on cache lookup
 * during the first login.
 */
public final class TokenCache {

    private final Map<String, JwtToken> tokens = new ConcurrentHashMap<>();

    public JwtToken getOrLogin(String username, String password, LobApiClient api) {
        return tokens.computeIfAbsent(username, u -> api.login(u, password));
    }

    public void invalidate(String username) {
        tokens.remove(username);
    }

    /** Test seam — true if {@code username} has a cached token. */
    public boolean has(String username) {
        return tokens.containsKey(username);
    }
}
