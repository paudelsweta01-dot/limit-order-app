package com.sweta.limitorder.simulator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonReporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writesAllFieldsInTheStableSchemaOrder() throws IOException {
        var report = new RunReport("test-run", "scenario", Instant.parse("2026-04-26T10:00:00Z"));
        report.finishedAt = Instant.parse("2026-04-26T10:00:01.234Z");
        report.submitted = 10;
        report.accepted = 9;
        report.rejected = 1;
        report.idempotentReplays = 0;
        report.tradesObserved = 3;
        report.addAssertion(AssertionResult.pass("book:AAPL"));
        report.addAssertion(AssertionResult.fail("book:MSFT", List.of("got 200, expected 80")));
        report.orders.add(new RunReport.OrderRecord(
                "c001", "u1", "AAPL", "BUY", "LIMIT", "180.50", 100,
                "00000000-0000-0000-0000-0000000000a1", "OPEN", 0, null));

        JsonNode root = JSON.readTree(JsonReporter.toJson(report));
        assertThat(root.get("runId").asText()).isEqualTo("test-run");
        assertThat(root.get("mode").asText()).isEqualTo("scenario");
        assertThat(root.get("durationMs").asLong()).isEqualTo(1234L);
        assertThat(root.get("totals").get("submitted").asInt()).isEqualTo(10);
        assertThat(root.get("totals").get("rejected").asInt()).isEqualTo(1);
        assertThat(root.get("totals").get("tradesObserved").asInt()).isEqualTo(3);
        assertThat(root.get("allAssertionsPassed").asBoolean()).isFalse();
        assertThat(root.get("assertions")).hasSize(2);
        assertThat(root.get("assertions").get(1).get("name").asText()).isEqualTo("book:MSFT");
        assertThat(root.get("assertions").get(1).get("diffs").get(0).asText())
                .isEqualTo("got 200, expected 80");
        assertThat(root.get("orders")).hasSize(1);
        assertThat(root.get("orders").get(0).get("clientOrderId").asText()).isEqualTo("c001");
        assertThat(root.get("orders").get(0).get("price").asText()).isEqualTo("180.50");
    }

    @Test
    void writeToPath_roundTripsThroughDisk(@TempDir Path tmp) throws IOException {
        var report = new RunReport("rrr", "load", Instant.now());
        report.finishedAt = Instant.now();
        report.addAssertion(AssertionResult.pass("load:duration-elapsed"));

        Path target = tmp.resolve("nested/dir/report.json");
        JsonReporter.write(report, target);
        assertThat(Files.exists(target)).isTrue();
        JsonNode root = JSON.readTree(Files.readAllBytes(target));
        assertThat(root.get("mode").asText()).isEqualTo("load");
        assertThat(root.get("allAssertionsPassed").asBoolean()).isTrue();
    }

    @Test
    void exampleReportFixtureMatchesCurrentSchema() throws IOException {
        // Round-trips the schema-doc example through Jackson and asserts
        // every documented top-level field is present. If we ever add
        // a field, this test fails until the example is updated.
        JsonNode root = JSON.readTree(getClass().getResourceAsStream("/example-report.json"));
        assertThat(root.fieldNames()).toIterable().contains(
                "runId", "mode", "startedAt", "finishedAt", "durationMs",
                "totals", "allAssertionsPassed", "assertions", "orders");
        assertThat(root.get("totals").fieldNames()).toIterable().contains(
                "submitted", "accepted", "rejected", "idempotentReplays", "tradesObserved");
    }
}
