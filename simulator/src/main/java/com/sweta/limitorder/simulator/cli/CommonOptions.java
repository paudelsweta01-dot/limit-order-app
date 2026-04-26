package com.sweta.limitorder.simulator.cli;

import java.nio.file.Path;

import picocli.CommandLine.Option;

/**
 * Options shared across every mode (plan §1.1). Subcommands import via
 * {@code @Mixin CommonOptions common;} and picocli wires them in.
 *
 * <ul>
 *   <li>{@code --baseUrl} — the LB endpoint (typically
 *       {@code http://localhost} when running against the docker-compose
 *       stack). Required so a stub run never accidentally hits a stale
 *       previous endpoint.</li>
 *   <li>{@code --report} — optional path for the JSON run report
 *       (Phase 7 of the simulator plan formalises the schema).</li>
 *   <li>{@code --logLevel} — root logger level for the run; picked up
 *       by Logback before any business logging fires.</li>
 * </ul>
 */
public class CommonOptions {

    @Option(
            names = "--baseUrl",
            description = "Backend base URL (e.g. http://localhost when running against docker-compose).",
            required = true
    )
    public String baseUrl;

    @Option(
            names = "--report",
            description = "Optional path for the JSON run report. Run still exits with the assertion code; "
                    + "the report records what was checked.",
            paramLabel = "PATH"
    )
    public Path report;

    @Option(
            names = "--logLevel",
            description = "Root log level (default: ${DEFAULT-VALUE}). One of: TRACE, DEBUG, INFO, WARN, ERROR.",
            defaultValue = "INFO"
    )
    public String logLevel;
}
