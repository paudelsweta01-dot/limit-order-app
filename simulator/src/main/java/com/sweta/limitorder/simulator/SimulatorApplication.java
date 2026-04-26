package com.sweta.limitorder.simulator;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Limit-order simulator entry point.
 *
 * <p>Phase 0 commits a runnable bootstrap: Spring Boot picks up the
 * picocli root command, prints {@code --help} cleanly, and exits with
 * the picocli exit code. Phase 1 adds the four subcommands
 * ({@code scenario}, {@code load}, {@code multi-instance},
 * {@code consistency-check}).
 *
 * <p>Architecture invariant: this module talks to the backend over
 * public HTTP / WebSocket APIs only — never imports backend code or
 * touches the database. Section §1 of the spec mandates this so the
 * simulator can credibly stand in for a third-party load tool.
 */
@SpringBootApplication
@Command(
        name = "simulator",
        mixinStandardHelpOptions = true,
        version = "limit-order-app simulator " + SimulatorApplication.VERSION,
        description = "Drives the limit-order backend over its public APIs. "
                + "Modes will be added in Phase 1: scenario, load, "
                + "multi-instance, consistency-check.",
        // Phase 1 wires the actual subcommand classes; for Phase 0 the
        // empty list keeps `--help` working and `--version` honest.
        subcommands = { CommandLine.HelpCommand.class }
)
public class SimulatorApplication implements CommandLineRunner, ExitCodeGenerator {

    static final String VERSION = "0.1.0-SNAPSHOT";

    private int exitCode = 0;

    public static void main(String[] args) {
        // SpringApplication.exit returns the ExitCodeGenerator's code,
        // so picocli's exit-on-help / exit-on-error semantics propagate
        // out to the shell. Without this, Spring Boot would always exit
        // 0 even after a `--help` invocation.
        System.exit(SpringApplication.exit(SpringApplication.run(SimulatorApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        // Default picocli factory (CommandLine.defaultFactory) instantiates
        // subcommands via reflection — fine for Phase 0 (no Spring-injected
        // subcommands yet). Phase 1 will swap in a Spring-aware IFactory
        // when the subcommand classes need ApiClient / WsClient beans.
        this.exitCode = new CommandLine(this).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
