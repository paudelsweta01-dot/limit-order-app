package com.sweta.limitorder.simulator.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.mode.ScenarioRunner;
import com.sweta.limitorder.simulator.mode.SeedCredentials;
import com.sweta.limitorder.simulator.report.AssertionResult;
import com.sweta.limitorder.simulator.report.RunReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §3 — replay a CSV of orders and assert the resulting book
 * snapshot. Drives {@link ScenarioRunner} and prints a console summary;
 * Phase 7 polishes the reporters.
 */
@Command(
        name = "scenario",
        description = "Replay a CSV of orders and assert the resulting book snapshot.",
        mixinStandardHelpOptions = true
)
public class ScenarioCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(
            names = "--file",
            description = "CSV file with seed orders (e.g. docs/requirnments/seed.csv).",
            required = true,
            paramLabel = "PATH"
    )
    public Path file;

    @Option(
            names = "--expect",
            description = "Optional JSON file with the expected snapshot to assert against (e.g. "
                    + "docs/requirnments/seed-expected-book.json).",
            paramLabel = "PATH"
    )
    public Path expected;

    @Option(
            names = "--credentials",
            description = "Optional CSV (`username,password`) for non-seed users; the four seed "
                    + "users from V3__seed_users are baked in.",
            paramLabel = "PATH"
    )
    public Path credentials;

    @Override
    public Integer call() throws IOException {
        RunContext ctx = RunContext.from(common);
        SeedCredentials creds = credentials != null
                ? SeedCredentials.fromCsv(credentials)
                : SeedCredentials.defaults();

        LobApiClient api = new LobApiClient(common.baseUrl);
        ScenarioRunner runner = new ScenarioRunner(api, new TokenCache(), creds);
        RunReport report = runner.run(file, expected, ctx.runId);

        // Console summary — Phase 7 will replace with the proper
        // ConsoleReporter; this is the minimum that makes the run useful.
        System.out.println("scenario run " + ctx.runId);
        System.out.printf("  duration:   %s%n", report.duration());
        System.out.printf("  submitted:  %d%n", report.submitted);
        System.out.printf("  accepted:   %d%n", report.accepted);
        System.out.printf("  rejected:   %d%n", report.rejected);
        if (!report.assertions.isEmpty()) {
            System.out.println("  assertions:");
            for (AssertionResult a : report.assertions) {
                System.out.printf("    [%s] %s%n", a.passed() ? "PASS" : "FAIL", a.name());
                for (String d : a.diffs()) System.out.println("      " + d);
            }
        }

        // Plan §1.3 exit code contract.
        return report.allAssertionsPassed() ? 0 : 1;
    }
}
