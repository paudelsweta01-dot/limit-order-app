package com.sweta.limitorder.simulator.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Plan §4 — assert the architecture §4.3 invariants against whatever
 * state currently exists in the backend (e.g. after a load run). Phase
 * 4 of the simulator plan lands the actual checks:
 * <ul>
 *   <li>Σ filled_qty(BUY) == Σ filled_qty(SELL) per symbol</li>
 *   <li>0 ≤ filled_qty ≤ quantity per order</li>
 *   <li>every trade references valid opposite-side orders</li>
 * </ul>
 *
 * <p>Exit 1 if any invariant is violated; exit 0 otherwise — that's
 * the contract that lets CI gate on it.
 */
@Command(
        name = "consistency-check",
        description = "Verify §4.3 invariants against the live backend; exit 1 on any violation.",
        mixinStandardHelpOptions = true
)
public class ConsistencyCheckCommand implements Callable<Integer> {

    @Mixin
    public CommonOptions common;

    @Override
    public Integer call() {
        RunContext ctx = RunContext.from(common);
        System.err.println("consistency-check: stub — Phase 4 of the simulator plan implements the invariant checks. "
                + "(runId=" + ctx.runId + ")");
        return 0;
    }
}
