package com.sweta.limitorder.simulator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsoleReporterTest {

    @Test
    void prints_modeRunIdHeaderAndCounters_andResultLine() {
        var report = new RunReport("run-123", "scenario", Instant.parse("2026-04-26T10:00:00Z"));
        report.finishedAt = Instant.parse("2026-04-26T10:00:01Z");
        report.submitted = 10;
        report.accepted = 10;
        report.rejected = 0;
        report.addAssertion(AssertionResult.pass("book:AAPL"));

        String out = capture(report, /* color */ false);
        assertThat(out)
                .contains("scenario run run-123")
                .contains("submitted:       10")
                .contains("accepted:        10")
                .contains("rejected:        0")
                .contains("[PASS] book:AAPL")
                .contains("result: PASS");
    }

    @Test
    void plaintext_mode_emitsNoAnsiEscapes() {
        var report = new RunReport("r", "scenario", Instant.now());
        report.addAssertion(AssertionResult.fail("book:AAPL", List.of("got 200, expected 80")));
        String out = capture(report, /* color */ false);
        assertThat(out)
                .as("plaintext mode must contain no ESC (\\u001B) byte")
                .doesNotContain("\u001B");
        assertThat(out).contains("[FAIL] book:AAPL").contains("got 200, expected 80");
    }

    @Test
    void color_mode_wrapsPassFailInAnsiCodes() {
        var report = new RunReport("r", "scenario", Instant.now());
        report.addAssertion(AssertionResult.pass("ok"));
        report.addAssertion(AssertionResult.fail("nope", List.of("a-diff")));
        String out = capture(report, /* color */ true);
        assertThat(out).contains("\u001B[32mPASS\u001B[0m");
        assertThat(out).contains("\u001B[31mFAIL\u001B[0m");
    }

    @Test
    void resultLineFollowsAssertions_failsOnAnyAssertionFailure() {
        var report = new RunReport("r", "load", Instant.now());
        report.finishedAt = Instant.now();
        report.addAssertion(AssertionResult.pass("a"));
        report.addAssertion(AssertionResult.fail("b", List.of("d")));
        String out = capture(report, false);
        assertThat(out).contains("result: FAIL");
    }

    private static String capture(RunReport report, boolean color) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        ConsoleReporter.print(report, ps, color);
        return baos.toString(StandardCharsets.UTF_8);
    }
}
