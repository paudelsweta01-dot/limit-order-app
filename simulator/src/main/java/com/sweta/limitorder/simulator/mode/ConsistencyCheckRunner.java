package com.sweta.limitorder.simulator.mode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sweta.limitorder.simulator.api.JwtToken;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.LobApiException;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.api.dto.MyFillResponse;
import com.sweta.limitorder.simulator.api.dto.MyOrderResponse;
import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.report.AssertionResult;
import com.sweta.limitorder.simulator.report.RunReport;

/**
 * Plan §4 — verifies the architecture §4.3 invariants against the live
 * backend through the public API surface only. Walks every configured
 * user's {@code getMyOrders} + {@code getMyFills} (login lazily), then
 * checks three invariants:
 *
 * <ol>
 *   <li>{@code Σ filled_qty(BUY) == Σ filled_qty(SELL)} per symbol —
 *       trades net out to zero on each side.</li>
 *   <li>No order has {@code filled_qty > quantity} (DB CHECK already
 *       enforces this; the assertion catches an API-side misreport).</li>
 *   <li>Every fill observed has a matching counterpart fill on the
 *       counterparty's account, with the same {@code tradeId},
 *       {@code symbol}, {@code price}, {@code quantity}, and opposite
 *       side. Cross-user agreement is what makes a trade a trade.</li>
 * </ol>
 *
 * <p>Each invariant lands in the {@link RunReport} as a PASS or FAIL
 * with a list of offending rows; the caller decides the exit code.
 */
public class ConsistencyCheckRunner {

    /** The seed users from V3__seed_users — covers the default deployment. */
    public static final List<String> DEFAULT_USERNAMES = List.of("u1", "u2", "u3", "u4");

    private final LobApiClient api;
    private final TokenCache tokens;
    private final SeedCredentials creds;
    private final List<String> usernames;

    public ConsistencyCheckRunner(LobApiClient api, TokenCache tokens, SeedCredentials creds) {
        this(api, tokens, creds, DEFAULT_USERNAMES);
    }

    public ConsistencyCheckRunner(LobApiClient api, TokenCache tokens, SeedCredentials creds,
                                  List<String> usernames) {
        this.api = api;
        this.tokens = tokens;
        this.creds = creds;
        this.usernames = List.copyOf(usernames);
    }

    public RunReport run(String runId) {
        RunReport report = new RunReport(runId, "consistency-check", Instant.now());
        Map<String, List<MyOrderResponse>> ordersByUser = new HashMap<>();
        Map<String, List<MyFillResponse>> fillsByUser = new HashMap<>();

        // Plan §4.1 — walk every user's orders and fills.
        for (String u : usernames) {
            try {
                JwtToken t = tokens.getOrLogin(u, creds.passwordFor(u), api);
                ordersByUser.put(u, api.getMyOrders(t));
                fillsByUser.put(u, api.getMyFills(t));
            } catch (LobApiException e) {
                report.addAssertion(AssertionResult.fail("walk:" + u,
                        List.of("API call failed for user " + u + ": " + e.getMessage())));
            }
        }

        report.addAssertion(checkBuySellNetZero(ordersByUser));
        report.addAssertion(checkFilledNotOverQuantity(ordersByUser));
        report.addAssertion(checkTradeCounterparts(fillsByUser));
        report.tradesObserved = countDistinctTrades(fillsByUser);

        report.finishedAt = Instant.now();
        return report;
    }

    // ---------- invariants ----------

    /** Plan §4.2 — Σ filled_qty(BUY) == Σ filled_qty(SELL) per symbol. */
    private static AssertionResult checkBuySellNetZero(Map<String, List<MyOrderResponse>> ordersByUser) {
        Map<String, long[]> bySymbol = new HashMap<>(); // [buyFilled, sellFilled]
        for (List<MyOrderResponse> rows : ordersByUser.values()) {
            for (MyOrderResponse r : rows) {
                long[] counts = bySymbol.computeIfAbsent(r.symbol(), k -> new long[2]);
                if (r.side() == OrderSide.BUY) counts[0] += r.filledQty();
                else counts[1] += r.filledQty();
            }
        }
        List<String> diffs = new ArrayList<>();
        bySymbol.forEach((symbol, counts) -> {
            if (counts[0] != counts[1]) {
                diffs.add(symbol + ": Σ filledQty(BUY)=" + counts[0]
                        + " ≠ Σ filledQty(SELL)=" + counts[1]
                        + " (diff=" + (counts[0] - counts[1]) + ")");
            }
        });
        return diffs.isEmpty()
                ? AssertionResult.pass("buy-sell-net-zero")
                : AssertionResult.fail("buy-sell-net-zero", diffs);
    }

    /** Plan §4.3 — no order has filled_qty > quantity. */
    private static AssertionResult checkFilledNotOverQuantity(
            Map<String, List<MyOrderResponse>> ordersByUser) {
        List<String> diffs = new ArrayList<>();
        ordersByUser.forEach((user, rows) -> {
            for (MyOrderResponse r : rows) {
                if (r.filledQty() > r.quantity()) {
                    diffs.add("order " + r.orderId()
                            + " (user=" + user + " sym=" + r.symbol() + "): "
                            + "filledQty=" + r.filledQty() + " > quantity=" + r.quantity());
                }
            }
        });
        return diffs.isEmpty()
                ? AssertionResult.pass("filled-le-quantity")
                : AssertionResult.fail("filled-le-quantity", diffs);
    }

    /**
     * Plan §4.4 — every fill has a matching opposite-side fill on the
     * named counterparty with same {tradeId, symbol, price, quantity}.
     */
    private static AssertionResult checkTradeCounterparts(Map<String, List<MyFillResponse>> fillsByUser) {
        // Index fills by tradeId × side × user — every tradeId should
        // have exactly two entries (one BUY, one SELL).
        Map<String, List<UserFill>> byTradeId = new HashMap<>();
        fillsByUser.forEach((user, rows) -> {
            for (MyFillResponse f : rows) {
                byTradeId.computeIfAbsent(f.tradeId().toString(), k -> new ArrayList<>())
                        .add(new UserFill(user, f));
            }
        });

        List<String> diffs = new ArrayList<>();
        byTradeId.forEach((tradeId, entries) -> {
            // Self-trades (architecture §9.7 known gap — prevention is
            // deferred) appear as a single fill row for the owning user
            // because /api/fills/mine joins trades→users and dedupes.
            // Recognise them by counterparty == owner and let them
            // through; they still satisfy "trade references a
            // counter-party order on the opposite side" — the
            // counter-party order just happens to be owned by the
            // same user.
            if (entries.size() == 1) {
                UserFill only = entries.get(0);
                if (only.fill.counterparty().equals(only.user)) {
                    return; // self-trade — pass
                }
                diffs.add("trade " + tradeId + ": expected exactly 2 fills (1 BUY + 1 SELL), "
                        + "got 1 (counterparty=" + only.fill.counterparty()
                        + " on " + only.user + "'s account — neither matches a self-trade)");
                return;
            }
            if (entries.size() != 2) {
                diffs.add("trade " + tradeId + ": expected exactly 2 fills (1 BUY + 1 SELL), "
                        + "got " + entries.size());
                return;
            }
            UserFill a = entries.get(0);
            UserFill b = entries.get(1);
            if (a.fill.side() == b.fill.side()) {
                diffs.add("trade " + tradeId + ": both sides are " + a.fill.side()
                        + " (users " + a.user + ", " + b.user + ")");
                return;
            }
            UserFill buy  = a.fill.side() == OrderSide.BUY  ? a : b;
            UserFill sell = a.fill.side() == OrderSide.SELL ? a : b;

            if (!buy.fill.symbol().equals(sell.fill.symbol())) {
                diffs.add("trade " + tradeId + ": symbol mismatch ("
                        + buy.fill.symbol() + " vs " + sell.fill.symbol() + ")");
            }
            if (buy.fill.price().compareTo(sell.fill.price()) != 0) {
                diffs.add("trade " + tradeId + ": price mismatch ("
                        + buy.fill.price() + " vs " + sell.fill.price() + ")");
            }
            if (buy.fill.quantity() != sell.fill.quantity()) {
                diffs.add("trade " + tradeId + ": quantity mismatch ("
                        + buy.fill.quantity() + " vs " + sell.fill.quantity() + ")");
            }
            if (!buy.fill.counterparty().equals(sell.user)) {
                diffs.add("trade " + tradeId + ": BUY-side counterparty="
                        + buy.fill.counterparty() + " but SELL fill is owned by " + sell.user);
            }
            if (!sell.fill.counterparty().equals(buy.user)) {
                diffs.add("trade " + tradeId + ": SELL-side counterparty="
                        + sell.fill.counterparty() + " but BUY fill is owned by " + buy.user);
            }
        });
        return diffs.isEmpty()
                ? AssertionResult.pass("trade-counterparts")
                : AssertionResult.fail("trade-counterparts", diffs);
    }

    private static int countDistinctTrades(Map<String, List<MyFillResponse>> fillsByUser) {
        Set<String> ids = new HashSet<>();
        fillsByUser.values().forEach(rows ->
                rows.forEach(r -> ids.add(r.tradeId().toString())));
        return ids.size();
    }

    private record UserFill(String user, MyFillResponse fill) {}
}
