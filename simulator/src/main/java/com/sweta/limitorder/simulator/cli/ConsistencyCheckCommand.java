package com.sweta.limitorder.simulator.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.mode.ConsistencyCheckRunner;
import com.sweta.limitorder.simulator.mode.SeedCredentials;
import com.sweta.limitorder.simulator.report.ConsoleReporter;
import com.sweta.limitorder.simulator.report.JsonReporter;
import com.sweta.limitorder.simulator.report.RunReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §4 — verify the architecture §4.3 invariants against whatever
 * state currently exists in the backend (e.g. after a load run).
 * Exit 1 on any invariant violation, 0 otherwise — the contract that
 * lets CI gate on it.
 */
@Command(
        name = "consistency-check",
        description = "Verify §4.3 invariants against the live backend; exit 1 on any violation.",
        mixinStandardHelpOptions = true
)
public class ConsistencyCheckCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(
            names = "--users",
            description = "Comma-separated usernames to walk (default: u1,u2,u3,u4 — the seed users).",
            paramLabel = "USERNAMES",
            split = ","
    )
    public List<String> users;

    @Option(
            names = "--credentials",
            description = "Optional CSV (`username,password`) for non-seed users.",
            paramLabel = "PATH"
    )
    public Path credentials;

    @Override
    public Integer call() throws IOException {
        RunContext ctx = RunContext.from(common);
        SeedCredentials creds = credentials != null
                ? SeedCredentials.fromCsv(credentials)
                : SeedCredentials.defaults();
        List<String> usernames = users != null && !users.isEmpty()
                ? users
                : ConsistencyCheckRunner.DEFAULT_USERNAMES;

        LobApiClient api = new LobApiClient(common.baseUrl);
        ConsistencyCheckRunner runner = new ConsistencyCheckRunner(api, new TokenCache(), creds, usernames);
        RunReport report = runner.run(ctx.runId);

        ConsoleReporter.print(report);
        if (common.report != null) JsonReporter.write(report, common.report);

        return report.allAssertionsPassed() ? 0 : 1;
    }
}
