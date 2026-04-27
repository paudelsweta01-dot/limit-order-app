package com.sweta.limitorder.simulator.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.mode.ConsistencyCheckRunner;
import com.sweta.limitorder.simulator.mode.LoadRunner;
import com.sweta.limitorder.simulator.mode.SeedCredentials;
import com.sweta.limitorder.simulator.report.ConsoleReporter;
import com.sweta.limitorder.simulator.report.JsonReporter;
import com.sweta.limitorder.simulator.report.RunReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §5 — load mode. Spawns N workers that submit orders at an
 * aggregate rate for {@code --duration}, then runs the §4.3
 * consistency-check (Phase 4) automatically (plan §5.4) unless
 * {@code --skip-consistency-check} is set.
 */
@Command(
        name = "load",
        description = "Run the §5.5 stress profile (configurable users, rate, duration).",
        mixinStandardHelpOptions = true
)
public class LoadCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(names = "--users",     description = "Concurrent user threads (default: ${DEFAULT-VALUE}).", defaultValue = "4")
    public int users;
    @Option(names = "--rate",      description = "Aggregate orders/sec across all users (default: ${DEFAULT-VALUE}).", defaultValue = "83")
    public int rate;
    @Option(names = "--duration",  description = "Run duration in seconds (default: ${DEFAULT-VALUE}).", defaultValue = "60")
    public int durationSeconds;
    @Option(names = "--seed",      description = "RNG seed for reproducible runs (default: ${DEFAULT-VALUE}).", defaultValue = "42")
    public long seed;
    @Option(names = "--credentials", description = "Optional CSV (`username,password`) for non-seed users.", paramLabel = "PATH")
    public Path credentials;
    @Option(names = "--skip-consistency-check",
            description = "Skip the post-run §4.3 consistency check (default: run it).")
    public boolean skipConsistencyCheck;

    @Override
    public Integer call() throws IOException {
        RunContext ctx = RunContext.from(common);
        SeedCredentials creds = credentials != null
                ? SeedCredentials.fromCsv(credentials)
                : SeedCredentials.defaults();

        LobApiClient api = new LobApiClient(common.baseUrl);
        TokenCache cache = new TokenCache();
        LoadRunner load = new LoadRunner(api, cache, creds,
                ConsistencyCheckRunner.DEFAULT_USERNAMES,
                users, rate, Duration.ofSeconds(durationSeconds), seed);

        // Plan §9.2 — graceful shutdown on SIGTERM / SIGINT. The hook flips
        // the runner's stop flag; main is blocked in load.run() polling
        // every 100 ms so it returns shortly after, falls through into the
        // post-run consistency check + report write, and the JVM
        // shutdown completes with a complete report on disk.
        Thread shutdownHook = new Thread(load::requestStop, "simulator-load-sigterm");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        RunReport report;
        try {
            report = load.run(ctx.runId);
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException alreadyShuttingDown) { /* hook already firing */ }
        }

        if (!skipConsistencyCheck) {
            ConsistencyCheckRunner check = new ConsistencyCheckRunner(api, cache, creds);
            RunReport checkReport = check.run(ctx.runId);
            // Plan §5.4: surface the consistency assertions in the load report
            // so a single exit code covers both halves.
            report.assertions.addAll(checkReport.assertions);
            report.tradesObserved += checkReport.tradesObserved;
        }

        ConsoleReporter.print(report);
        if (common.report != null) JsonReporter.write(report, common.report);

        return report.allAssertionsPassed() ? 0 : 1;
    }
}
