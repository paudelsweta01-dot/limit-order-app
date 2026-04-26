package com.sweta.limitorder.simulator.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §5 — generate the §5.5 stress profile. Phase 5 of the simulator
 * plan lands the actual workload + invariant checks.
 */
@Command(
        name = "load",
        description = "Run the §5.5 stress profile (configurable users, rate, duration).",
        mixinStandardHelpOptions = true
)
public class LoadCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(
            names = "--users",
            description = "Number of concurrent users (default: ${DEFAULT-VALUE}).",
            defaultValue = "4"
    )
    public int users;

    @Option(
            names = "--rate",
            description = "Target orders per second across all users (default: ${DEFAULT-VALUE}).",
            defaultValue = "83"
    )
    public int rate;

    @Option(
            names = "--duration",
            description = "Run duration in seconds (default: ${DEFAULT-VALUE}).",
            defaultValue = "60"
    )
    public int duration;

    @Override
    public Integer call() {
        RunContext ctx = RunContext.from(common);
        System.err.println("load: stub — Phase 5 of the simulator plan implements the stress profile. "
                + "(runId=" + ctx.runId + ")");
        return 0;
    }
}
