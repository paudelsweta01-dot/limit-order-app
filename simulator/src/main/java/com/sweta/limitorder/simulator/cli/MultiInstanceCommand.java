package com.sweta.limitorder.simulator.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.mode.ConsistencyCheckRunner;
import com.sweta.limitorder.simulator.mode.MultiInstanceRunner;
import com.sweta.limitorder.simulator.mode.SeedCredentials;
import com.sweta.limitorder.simulator.report.ConsoleReporter;
import com.sweta.limitorder.simulator.report.JsonReporter;
import com.sweta.limitorder.simulator.report.RunReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §6 — drive load against two nodes simultaneously, then assert
 * their books converge (architecture §3 NFR), and finish with the
 * §4.3 consistency-check (Phase 4).
 *
 * <p>Note on the inherited {@code --baseUrl}: this mode actually splits
 * traffic between {@code --nodeA} and {@code --nodeB}. The
 * {@code --baseUrl} from {@link CommonOptions} is used for the
 * post-run consistency-check call (any LB-fronted endpoint with full
 * data works — typically {@code --baseUrl=--nodeA}).
 */
@Command(
        name = "multi-instance",
        description = "Drive both backend nodes simultaneously and check book convergence post-run.",
        mixinStandardHelpOptions = true
)
public class MultiInstanceCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(names = "--nodeA", description = "Endpoint for backend node A.", required = true)
    public String nodeA;

    @Option(names = "--nodeB", description = "Endpoint for backend node B.", required = true)
    public String nodeB;

    @Option(names = "--users",    defaultValue = "4", description = "Concurrent worker threads (default: ${DEFAULT-VALUE}).")
    public int users;
    @Option(names = "--rate",     defaultValue = "83", description = "Aggregate orders/sec across all workers (default: ${DEFAULT-VALUE}).")
    public int rate;
    @Option(names = "--duration", defaultValue = "60", description = "Run duration in seconds (default: ${DEFAULT-VALUE}).")
    public int durationSeconds;
    @Option(names = "--seed",     defaultValue = "42", description = "RNG seed (default: ${DEFAULT-VALUE}).")
    public long seed;
    @Option(names = "--credentials", description = "Optional CSV (`username,password`) for non-seed users.", paramLabel = "PATH")
    public Path credentials;

    @Override
    public Integer call() throws IOException {
        RunContext ctx = RunContext.from(common);
        SeedCredentials creds = credentials != null
                ? SeedCredentials.fromCsv(credentials)
                : SeedCredentials.defaults();

        LobApiClient apiA = new LobApiClient(nodeA);
        LobApiClient apiB = new LobApiClient(nodeB);
        TokenCache cache = new TokenCache();

        MultiInstanceRunner mi = new MultiInstanceRunner(apiA, apiB, cache, creds,
                ConsistencyCheckRunner.DEFAULT_USERNAMES,
                users, rate, Duration.ofSeconds(durationSeconds), seed);
        RunReport report = mi.run(ctx.runId);

        // Plan §6.4 — same end-of-run consistency check as Phase 4.
        // Hits --baseUrl (typically the LB) so the data is the union
        // across both nodes (architecture §4.7 fan-out makes this work).
        ConsistencyCheckRunner check = new ConsistencyCheckRunner(
                new LobApiClient(common.baseUrl), cache, creds);
        RunReport checkReport = check.run(ctx.runId);
        report.assertions.addAll(checkReport.assertions);
        report.tradesObserved += checkReport.tradesObserved;

        ConsoleReporter.print(report);
        if (common.report != null) JsonReporter.write(report, common.report);

        return report.allAssertionsPassed() ? 0 : 1;
    }
}
