package com.sweta.limitorder.simulator.mode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Username → password lookup for the simulator. The four seed users
 * from the backend's {@code V3__seed_users} migration are baked in so
 * a clean compose-up can be exercised against the canonical
 * {@code docs/requirnments/seed.csv} without extra arguments. A
 * {@code --credentials=PATH} file (CSV: {@code username,password})
 * can override / extend this for non-seed users.
 */
public final class SeedCredentials {

    private final Map<String, String> creds;

    private SeedCredentials(Map<String, String> creds) {
        this.creds = creds;
    }

    public static SeedCredentials defaults() {
        Map<String, String> m = new HashMap<>();
        // Mirrors backend/.../V3__seed_users.java exactly. If those
        // change, this map must too — picked up via the simulator's
        // integration runs against the live backend (Phase 6).
        m.put("u1", "alice123");
        m.put("u2", "bob123");
        m.put("u3", "charlie123");
        m.put("u4", "diana123");
        return new SeedCredentials(m);
    }

    /** Loads {@code username,password} pairs from a CSV file (header skipped if present). */
    public static SeedCredentials fromCsv(Path file) throws IOException {
        Map<String, String> m = new HashMap<>(defaults().creds);
        boolean headerSkipped = false;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (!headerSkipped && trimmed.toLowerCase().startsWith("username,")) {
                headerSkipped = true;
                continue;
            }
            int comma = trimmed.indexOf(',');
            if (comma < 0) continue;
            m.put(trimmed.substring(0, comma).strip(), trimmed.substring(comma + 1).strip());
        }
        return new SeedCredentials(m);
    }

    public String passwordFor(String username) {
        String pw = creds.get(username);
        if (pw == null) throw new IllegalArgumentException(
                "no password configured for user '" + username + "'; "
                        + "add a row to --credentials, or use one of the seed users (u1..u4)");
        return pw;
    }

    public boolean knows(String username) {
        return creds.containsKey(username);
    }
}
