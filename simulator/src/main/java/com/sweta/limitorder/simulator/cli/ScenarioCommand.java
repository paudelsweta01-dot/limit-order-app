package com.sweta.limitorder.simulator.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §3 — replay a CSV of orders against the backend and assert the
 * resulting book snapshot matches an expected JSON. Phase 1 stubs this
 * out so {@code simulator scenario --help} works; Phase 3 of the
 * simulator plan lands the actual replay + assertion logic.
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
            description = "Optional JSON file with the expected snapshot to assert against.",
            paramLabel = "PATH"
    )
    public Path expected;

    @Override
    public Integer call() {
        RunContext ctx = RunContext.from(common);
        System.err.println("scenario: stub — Phase 3 of the simulator plan implements the replay + assertion. "
                + "(runId=" + ctx.runId + ")");
        return 0;
    }
}
