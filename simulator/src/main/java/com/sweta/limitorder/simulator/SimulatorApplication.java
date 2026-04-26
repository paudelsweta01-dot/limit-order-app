package com.sweta.limitorder.simulator;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.sweta.limitorder.simulator.cli.ConsistencyCheckCommand;
import com.sweta.limitorder.simulator.cli.LoadCommand;
import com.sweta.limitorder.simulator.cli.MultiInstanceCommand;
import com.sweta.limitorder.simulator.cli.ScenarioCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Limit-order simulator entry point.
 *
 * <p>Spring Boot starts → picocli parses argv → the matching subcommand
 * runs and returns an int → that int is propagated through
 * {@link ExitCodeGenerator} so the shell sees picocli's exit code, not
 * Spring Boot's default 0.
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
                + "Pick a mode: scenario, load, multi-instance, consistency-check.",
        subcommands = {
                ScenarioCommand.class,
                LoadCommand.class,
                MultiInstanceCommand.class,
                ConsistencyCheckCommand.class,
                CommandLine.HelpCommand.class
        },
        // Plan §1.3 exit code contract — picocli's defaults map cleanly,
        // we just need to override `exitCodeOnExecutionException` from
        // its default (1) to 3 so unexpected runtime errors are
        // distinguishable from assertion failures.
        exitCodeOnExecutionException = SimulatorApplication.EXIT_RUNTIME_ERROR,
        footer = {
                "",
                "Exit codes (plan §1.3):",
                "  0  success / all assertions passed",
                "  1  assertion failed",
                "  2  configuration error (bad/missing argument)",
                "  3  unexpected runtime error",
                ""
        }
)
public class SimulatorApplication implements CommandLineRunner, ExitCodeGenerator {

    static final String VERSION = "0.1.0-SNAPSHOT";
    static final int EXIT_RUNTIME_ERROR = 3;

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
        // Default picocli factory instantiates subcommands via reflection
        // — fine for Phase 1 (subcommand stubs need no Spring DI yet).
        // Phase 2 will swap in a Spring-aware IFactory when the body of
        // each subcommand starts depending on Lob{Api,Ws}Client beans.
        this.exitCode = new CommandLine(this).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
