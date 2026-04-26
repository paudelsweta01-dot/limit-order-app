package com.sweta.limitorder.simulator.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Plan §6 — drive load across two backend nodes simultaneously and
 * assert their books converge within 1 s of run end (architecture §3
 * NFR). Phase 6 of the simulator plan lands the multi-node coordinator.
 */
@Command(
        name = "multi-instance",
        description = "Drive both backend nodes simultaneously and check book convergence post-run.",
        mixinStandardHelpOptions = true
)
public class MultiInstanceCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Option(
            names = "--nodeA",
            description = "Endpoint for backend node A (e.g. http://localhost via the LB, or a backend's direct port).",
            required = true
    )
    public String nodeA;

    @Option(
            names = "--nodeB",
            description = "Endpoint for backend node B.",
            required = true
    )
    public String nodeB;

    @Override
    public Integer call() {
        RunContext ctx = RunContext.from(common);
        System.err.println("multi-instance: stub — Phase 6 of the simulator plan implements the cross-node coordinator. "
                + "(runId=" + ctx.runId + ")");
        return 0;
    }
}
