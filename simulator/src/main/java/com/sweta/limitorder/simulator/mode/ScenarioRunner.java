package com.sweta.limitorder.simulator.mode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sweta.limitorder.simulator.api.JwtToken;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.LobApiException;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.api.dto.BookLevel;
import com.sweta.limitorder.simulator.api.dto.BookSnapshot;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderResponse;
import com.sweta.limitorder.simulator.report.AssertionResult;
import com.sweta.limitorder.simulator.report.RunReport;

/**
 * Plan §3 — Scenario mode logic. Reads a CSV, replays each row against
 * the backend (logging users in lazily via {@link TokenCache}), then
 * (optionally) compares the resulting book(s) to an expected JSON.
 *
 * <p>This class is intentionally framework-free: takes its
 * dependencies via the constructor so unit tests can pass a
 * WireMock-backed {@link LobApiClient}.
 */
public class ScenarioRunner {

    private final LobApiClient api;
    private final TokenCache tokens;
    private final SeedCredentials creds;
    private final ObjectMapper json;

    public ScenarioRunner(LobApiClient api, TokenCache tokens, SeedCredentials creds) {
        this.api = api;
        this.tokens = tokens;
        this.creds = creds;
        this.json = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    /**
     * Replay {@code csvFile} and (if non-null) assert against
     * {@code expectedJson}. Returns a populated {@link RunReport}; the
     * caller decides what exit code to use based on
     * {@link RunReport#allAssertionsPassed()}.
     */
    public RunReport run(Path csvFile, Path expectedJson, String runId) throws IOException {
        RunReport report = new RunReport(runId, "scenario", Instant.now());
        List<ScenarioCsv.Row> rows = ScenarioCsv.parse(csvFile);

        for (ScenarioCsv.Row row : rows) {
            submitOne(row, report);
        }

        if (expectedJson != null) {
            assertBookMatches(rows, expectedJson, report);
        }

        report.finishedAt = Instant.now();
        return report;
    }

    // ---------- per-row submission ----------

    private void submitOne(ScenarioCsv.Row row, RunReport report) {
        report.submitted++;
        try {
            JwtToken token = tokens.getOrLogin(row.userId(), creds.passwordFor(row.userId()), api);
            SubmitOrderResponse res = api.submit(new SubmitOrderRequest(
                    row.clientOrderId(), row.symbol(), row.side(), row.type(),
                    row.price(), row.quantity()), token);
            report.orders.add(new RunReport.OrderRecord(
                    row.clientOrderId(), row.userId(), row.symbol(),
                    row.side().name(), row.type().name(),
                    row.price() != null ? row.price().toPlainString() : null,
                    row.quantity(),
                    res.orderId() != null ? res.orderId().toString() : null,
                    res.status() != null ? res.status().name() : null,
                    res.filledQty(),
                    null));
            if (res.idempotentReplay()) report.idempotentReplays++;
            if (res.status() != null) {
                switch (res.status()) {
                    case OPEN, PARTIAL, FILLED -> report.accepted++;
                    case CANCELLED, REJECTED -> report.rejected++;
                }
            }
        } catch (LobApiException e) {
            report.rejected++;
            report.orders.add(new RunReport.OrderRecord(
                    row.clientOrderId(), row.userId(), row.symbol(),
                    row.side().name(), row.type().name(),
                    row.price() != null ? row.price().toPlainString() : null,
                    row.quantity(),
                    null, "ERROR", 0,
                    e.envelope() != null ? e.envelope().message() : e.getMessage()));
        }
    }

    // ---------- book assertion ----------

    private void assertBookMatches(List<ScenarioCsv.Row> rows, Path expectedJson, RunReport report) throws IOException {
        JsonNode expected = json.readTree(Files.readAllBytes(expectedJson));
        Set<String> symbols = new LinkedHashSet<>();
        rows.forEach(r -> symbols.add(r.symbol()));
        // Also check any symbols mentioned in the expected file (e.g. AMZN
        // — the §5.4 fixture asserts an empty book on a symbol that doesn't
        // appear in the seed CSV).
        expected.fieldNames().forEachRemaining(name -> {
            if (!name.startsWith("_")) symbols.add(name); // skip _doc / _comment
        });

        // Backend requires auth on /api/book/{symbol}; pick any submitter
        // from the CSV to source a JWT (TokenCache already has it cached
        // from the submission loop above).
        String reader = rows.isEmpty() ? "u1" : rows.get(0).userId();
        JwtToken token;
        try {
            token = tokens.getOrLogin(reader, creds.passwordFor(reader), api);
        } catch (LobApiException e) {
            report.addAssertion(AssertionResult.fail("book:read-auth",
                    List.of("failed to log in as " + reader + " for book reads: " + e.getMessage())));
            return;
        }

        for (String symbol : symbols) {
            JsonNode expectedSym = expected.get(symbol);
            if (expectedSym == null) continue; // symbol not asserted
            BookSnapshot actual;
            try {
                actual = api.getBook(symbol, token);
            } catch (LobApiException e) {
                report.addAssertion(AssertionResult.fail("book:" + symbol,
                        List.of("getBook(" + symbol + ") failed: " + e.getMessage())));
                continue;
            }
            List<String> diffs = new ArrayList<>();
            compareLevels(symbol, "bids", expectedSym.get("bids"), actual.bids(), diffs);
            compareLevels(symbol, "asks", expectedSym.get("asks"), actual.asks(), diffs);
            if (diffs.isEmpty()) report.addAssertion(AssertionResult.pass("book:" + symbol));
            else report.addAssertion(AssertionResult.fail("book:" + symbol, diffs));
        }
    }

    private static void compareLevels(String symbol, String side,
                                      JsonNode expectedArr, List<BookLevel> actualLevels,
                                      List<String> diffs) {
        if (expectedArr == null || !expectedArr.isArray()) return;
        int n = Math.max(expectedArr.size(), actualLevels.size());
        for (int i = 0; i < n; i++) {
            JsonNode expectedLevel = i < expectedArr.size() ? expectedArr.get(i) : null;
            BookLevel actualLevel = i < actualLevels.size() ? actualLevels.get(i) : null;
            if (!equal(expectedLevel, actualLevel)) {
                diffs.add(symbol + "." + side + "[" + i + "]: expected "
                        + renderExpected(expectedLevel) + ", got " + renderActual(actualLevel));
            }
        }
    }

    /**
     * Numeric equality on price (compareTo ignores scale; backend
     * returns NUMERIC(12,4) so {@code 180.0000} must compare equal to
     * the expected fixture's {@code 180.00}). Long equality on qty
     * and userCount.
     */
    private static boolean equal(JsonNode expectedLevel, BookLevel actualLevel) {
        if (expectedLevel == null && actualLevel == null) return true;
        if (expectedLevel == null || actualLevel == null) return false;
        BigDecimal expectedPrice = new BigDecimal(expectedLevel.get("price").asText());
        if (expectedPrice.compareTo(actualLevel.price()) != 0) return false;
        if (expectedLevel.get("qty").asLong() != actualLevel.qty()) return false;
        return expectedLevel.get("userCount").asInt() == actualLevel.userCount();
    }

    private static String renderExpected(JsonNode lvl) {
        if (lvl == null) return "(empty)";
        return "price=" + new BigDecimal(lvl.get("price").asText()).toPlainString()
                + " qty=" + lvl.get("qty").asLong()
                + " userCount=" + lvl.get("userCount").asInt();
    }

    private static String renderActual(BookLevel lvl) {
        if (lvl == null) return "(empty)";
        return "price=" + lvl.price().toPlainString()
                + " qty=" + lvl.qty()
                + " userCount=" + lvl.userCount();
    }

    /** Test seam — generate a fresh runId. */
    public static String newRunId() {
        return UUID.randomUUID().toString();
    }
}
