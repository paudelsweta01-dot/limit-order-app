package com.sweta.limitorder.simulator.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator for a single run. Phases 3–6 populate it as
 * orders are submitted and assertions run; Phase 7 dumps it via
 * {@code ConsoleReporter} (stdout summary) and {@code JsonReporter}
 * ({@code --report=PATH}). Schema documented in
 * {@code simulator/REPORT_SCHEMA.md}.
 */
public final class RunReport {

    public final String runId;
    public final String mode;
    public final Instant startedAt;
    public Instant finishedAt;

    /** Plan §3.4: each order submitted with request + response (or error message). */
    public final List<OrderRecord> orders = new ArrayList<>();
    public final List<AssertionResult> assertions = new ArrayList<>();

    public int submitted = 0;
    public int accepted  = 0;     // status OPEN / PARTIAL / FILLED
    public int rejected  = 0;     // 4xx → rejected; status REJECTED also counts
    public int idempotentReplays = 0;
    public int tradesObserved = 0;

    public RunReport(String runId, String mode, Instant startedAt) {
        this.runId = runId;
        this.mode = mode;
        this.startedAt = startedAt;
    }

    public void addAssertion(AssertionResult a) {
        assertions.add(a);
    }

    /** True iff every recorded assertion passed. */
    public boolean allAssertionsPassed() {
        return assertions.stream().allMatch(AssertionResult::passed);
    }

    public Duration duration() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    /**
     * Per-order entry. {@code clientOrderId} is the natural key — the
     * server's {@code orderId} only exists once the engine accepts.
     */
    public record OrderRecord(
            String clientOrderId,
            String userId,
            String symbol,
            String side,
            String type,
            String price,           // string so 4dp BigDecimal precision is preserved on the wire
            long quantity,
            String orderId,         // null on submission failure
            String status,          // OPEN/PARTIAL/FILLED/CANCELLED/REJECTED, or "ERROR"
            long filledQty,
            String error            // §4.11 envelope message on submission failure, else null
    ) {}
}
