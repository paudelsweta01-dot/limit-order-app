package com.sweta.limitorder.simulator.report;

import java.util.List;

/**
 * One pass/fail line in the {@link RunReport}. {@code diffs} is empty
 * on PASS; on FAIL it carries human-readable strings ready to print:
 *
 * <pre>
 * AAPL.asks[0]: expected price=180.50 qty=80 userCount=1, got price=180.50 qty=200 userCount=1
 * </pre>
 */
public record AssertionResult(String name, boolean passed, List<String> diffs) {

    public static AssertionResult pass(String name) {
        return new AssertionResult(name, true, List.of());
    }

    public static AssertionResult fail(String name, List<String> diffs) {
        return new AssertionResult(name, false, List.copyOf(diffs));
    }
}
