package com.sweta.limitorder.simulator.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Plan §7.2 — writes a {@link RunReport} as JSON to {@code --report=PATH}.
 * Schema documented in {@code simulator/REPORT_SCHEMA.md}; an example
 * output ships at {@code simulator/src/test/resources/example-report.json}.
 *
 * <p>The on-disk shape uses a stable, hand-rolled key order ({@code runId},
 * {@code mode}, timestamps, counters, assertions, orders) rather than
 * Jackson's reflection-driven order so reviewers can diff reports
 * across runs without spurious noise.
 */
public final class JsonReporter {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private JsonReporter() {}

    /** Write {@code report} as pretty-printed JSON to {@code path}. */
    public static void write(RunReport report, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            // `Files.createDirectories` chokes on macOS' /tmp symlink
            // (rejects intermediate components that already exist as
            // symlinks); guarding with isDirectory short-circuits the
            // common "parent already exists" case while still creating
            // fresh dirs for nested paths.
            Files.createDirectories(parent);
        }
        Files.writeString(path, toJson(report));
    }

    public static String toJson(RunReport report) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId",         report.runId);
        root.put("mode",          report.mode);
        root.put("startedAt",     report.startedAt.toString());
        root.put("finishedAt",    report.finishedAt != null ? report.finishedAt.toString() : null);
        root.put("durationMs",    report.duration().toMillis());

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("submitted",         report.submitted);
        totals.put("accepted",          report.accepted);
        totals.put("rejected",          report.rejected);
        totals.put("idempotentReplays", report.idempotentReplays);
        totals.put("tradesObserved",    report.tradesObserved);
        root.put("totals", totals);

        root.put("allAssertionsPassed", report.allAssertionsPassed());
        root.put("assertions", report.assertions.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.name());
            m.put("passed", a.passed());
            m.put("diffs", List.copyOf(a.diffs()));
            return m;
        }).collect(Collectors.toUnmodifiableList()));

        root.put("orders", report.orders);

        return JSON.writeValueAsString(root);
    }
}
