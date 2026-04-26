package com.sweta.limitorder.simulator.cli;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Common state every mode needs (plan §1.2). Built once per invocation
 * from {@link CommonOptions} and passed to the subcommand's body.
 *
 * <p>Phase 2 will extend this with {@code LobApiClient} and (Phase 4+)
 * a {@code LobWsClient} once those classes exist; for now we carry only
 * the things known at startup so Phase 1's plumbing compiles in
 * isolation.
 */
public final class RunContext {

    public final String baseUrl;
    public final String runId;
    public final Instant startedAt;
    public final Optional<Path> reportPath;

    private RunContext(String baseUrl, String runId, Instant startedAt, Optional<Path> reportPath) {
        this.baseUrl = baseUrl;
        this.runId = runId;
        this.startedAt = startedAt;
        this.reportPath = reportPath;
    }

    /**
     * Snapshot the CommonOptions into a context. Stamps a fresh
     * {@code runId} (UUID — used to correlate report file names and
     * any log lines emitted during the run).
     */
    public static RunContext from(CommonOptions options) {
        return new RunContext(
                options.baseUrl,
                UUID.randomUUID().toString(),
                Instant.now(),
                Optional.ofNullable(options.report)
        );
    }
}
