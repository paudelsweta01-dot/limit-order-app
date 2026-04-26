package com.sweta.limitorder.simulator.report;

import java.io.PrintStream;

/**
 * Plan §7.1 — pretty-prints a {@link RunReport} as a parseable text
 * summary. ANSI color is emitted only when stdout is attached to an
 * interactive terminal ({@code System.console() != null}); piped /
 * redirected output gets plain text so log scrapers stay sane.
 */
public final class ConsoleReporter {

    // ANSI escapes — the leading byte is ESC (0x1B). Wraps only the
    // PASS/FAIL tokens so the rest of the line stays plain text.
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED   = "\u001B[31m";

    private ConsoleReporter() {}

    public static void print(RunReport report) {
        print(report, System.out, System.console() != null);
    }

    public static void print(RunReport report, PrintStream out, boolean color) {
        String pass = color ? GREEN + "PASS" + RESET : "PASS";
        String fail = color ? RED   + "FAIL" + RESET : "FAIL";

        out.printf("%s run %s%n", report.mode, report.runId);
        out.printf("  duration:        %s%n", report.duration());
        out.printf("  submitted:       %d%n", report.submitted);
        out.printf("  accepted:        %d%n", report.accepted);
        out.printf("  rejected:        %d%n", report.rejected);
        if (report.tradesObserved > 0) {
            out.printf("  trades observed: %d%n", report.tradesObserved);
        }
        if (report.idempotentReplays > 0) {
            out.printf("  idempotent replays: %d%n", report.idempotentReplays);
        }
        if (!report.assertions.isEmpty()) {
            out.println("  assertions:");
            for (AssertionResult a : report.assertions) {
                out.printf("    [%s] %s%n", a.passed() ? pass : fail, a.name());
                for (String diff : a.diffs()) out.println("      " + diff);
            }
        }
        out.printf("  result: %s%n", report.allAssertionsPassed() ? pass : fail);
    }
}
