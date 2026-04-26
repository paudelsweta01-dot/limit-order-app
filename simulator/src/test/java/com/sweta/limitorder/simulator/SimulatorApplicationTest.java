package com.sweta.limitorder.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * Phase 1 acceptance — picocli root parses cleanly, the four mode
 * subcommands are registered, every subcommand has its own help, and
 * the exit-code contract is documented in the root --help footer.
 *
 * <p>Tests don't go through Spring Boot — they exercise picocli
 * directly via {@code new CommandLine(new SimulatorApplication())},
 * which is fast and deterministic. The Spring side of the wiring is
 * verified manually by the run-the-fat-jar smoke in this session's
 * commit message.
 */
class SimulatorApplicationTest {

    @Test
    void rootHelpListsAllFourModeSubcommands() {
        String output = runAndCapture("--help");
        assertThat(output)
                .contains("scenario")
                .contains("load")
                .contains("multi-instance")
                .contains("consistency-check");
    }

    @Test
    void rootHelpFooterDocumentsTheExitCodeContract() {
        String output = runAndCapture("--help");
        assertThat(output)
                .contains("Exit codes")
                .contains("0  success")
                .contains("1  assertion failed")
                .contains("2  configuration error")
                .contains("3  unexpected runtime error");
    }

    @Test
    void scenarioSubcommandHasItsOwnHelpWithFileAndExpectOptions() {
        String output = runAndCapture("scenario", "--help");
        assertThat(output)
                .contains("--file")
                .contains("--expect")
                .contains("--baseUrl"); // CommonOptions mixin pulled in
    }

    @Test
    void loadSubcommandExposesUsersRateDuration() {
        String output = runAndCapture("load", "--help");
        assertThat(output)
                .contains("--users")
                .contains("--rate")
                .contains("--duration");
    }

    @Test
    void multiInstanceSubcommandRequiresBothNodeUrls() {
        String output = runAndCapture("multi-instance", "--help");
        assertThat(output)
                .contains("--nodeA")
                .contains("--nodeB");
    }

    @Test
    void consistencyCheckSubcommandHasItsOwnHelp() {
        String output = runAndCapture("consistency-check", "--help");
        assertThat(output).contains("§4.3 invariants");
    }

    @Test
    void missingRequiredBaseUrlExits2_configurationError() {
        int exitCode = runForExit("scenario", "--file=does-not-matter.csv");
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void unknownOptionExits2_configurationError() {
        int exitCode = runForExit("--bogus");
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void helpExitsZero() {
        int exitCode = runForExit("--help");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void stubSubcommandRunReturnsZero_noAssertionsToCheck() {
        // Phase 1 stubs print "stub — Phase X" and exit 0 because they
        // have no assertions to evaluate. Phase 3+ overrides this with
        // real exit-code semantics.
        int exitCode = runForExit("consistency-check", "--baseUrl=http://localhost");
        assertThat(exitCode).isEqualTo(0);
    }

    // ---------- helpers ----------

    private static String runAndCapture(String... args) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        CommandLine cmd = new CommandLine(new SimulatorApplication());
        cmd.setOut(pw);
        cmd.setErr(pw);
        cmd.execute(args);
        pw.flush();
        return sw.toString();
    }

    private static int runForExit(String... args) {
        // Suppress output by piping to /dev/null-equivalent writers so
        // the test log stays clean.
        StringWriter sink = new StringWriter();
        PrintWriter pw = new PrintWriter(sink);
        CommandLine cmd = new CommandLine(new SimulatorApplication());
        cmd.setOut(pw);
        cmd.setErr(pw);
        return cmd.execute(args);
    }
}
