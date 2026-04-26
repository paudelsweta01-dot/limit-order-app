package com.sweta.limitorder.simulator.mode;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.report.AssertionResult;

/**
 * Plan §4 acceptance — the three §4.3 invariants assert PASS for a
 * clean state and FAIL with an offending-row diff for fault injection.
 *
 * <p>Stubs work like this: login response is keyed off the request
 * body's username so each user gets a distinct token. The orders /
 * fills stubs then match on the Authorization header to dispatch the
 * right per-user response.
 */
class ConsistencyCheckRunnerTest {

    private WireMockServer wm;
    private ConsistencyCheckRunner runner;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        // Per-user login stubs — body match dispatches a unique token.
        for (String user : List.of("u1", "u2")) {
            wm.stubFor(post(urlEqualTo("/api/auth/login"))
                    .withRequestBody(matchingJsonPath("$.username", equalTo(user)))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"token":"jwt-%s","userId":"00000000-0000-0000-0000-000000000001","name":"X"}
                                    """.formatted(user))));
        }
        runner = new ConsistencyCheckRunner(
                new LobApiClient("http://localhost:" + wm.port()),
                new TokenCache(), SeedCredentials.defaults(), List.of("u1", "u2"));
    }

    @AfterEach
    void tearDown() { if (wm != null) wm.stop(); }

    @Test
    void cleanState_allInvariantsPass() {
        // u1 BUY filled 100; u2 SELL filled 100.
        stubOrders("u1", """
                [{"orderId":"00000000-0000-0000-0000-000000000a01","clientOrderId":"c1",
                  "symbol":"AAPL","side":"BUY","type":"LIMIT","price":180.50,
                  "quantity":100,"filledQty":100,"status":"FILLED",
                  "createdAt":"2026-04-26T10:00:00Z","updatedAt":"2026-04-26T10:00:01Z"}]
                """);
        stubOrders("u2", """
                [{"orderId":"00000000-0000-0000-0000-000000000a02","clientOrderId":"c2",
                  "symbol":"AAPL","side":"SELL","type":"LIMIT","price":180.50,
                  "quantity":100,"filledQty":100,"status":"FILLED",
                  "createdAt":"2026-04-26T09:59:00Z","updatedAt":"2026-04-26T10:00:01Z"}]
                """);
        stubFills("u1", """
                [{"tradeId":"00000000-0000-0000-0000-00000000ad01","symbol":"AAPL",
                  "side":"BUY","price":180.50,"quantity":100,
                  "executedAt":"2026-04-26T10:00:01Z","counterparty":"u2"}]
                """);
        stubFills("u2", """
                [{"tradeId":"00000000-0000-0000-0000-00000000ad01","symbol":"AAPL",
                  "side":"SELL","price":180.50,"quantity":100,
                  "executedAt":"2026-04-26T10:00:01Z","counterparty":"u1"}]
                """);

        var report = runner.run("test-run");
        assertThat(report.allAssertionsPassed())
                .as("expected all invariants to pass; report=%s", report.assertions)
                .isTrue();
        assertThat(report.tradesObserved).isEqualTo(1);
        assertThat(report.assertions).extracting(AssertionResult::name)
                .containsExactly("buy-sell-net-zero", "filled-le-quantity", "trade-counterparts");
    }

    @Test
    void buySellMismatch_failsInvariant1WithSymbolDiff() {
        stubOrders("u1", """
                [{"orderId":"00000000-0000-0000-0000-000000000a01","clientOrderId":"c1",
                  "symbol":"AAPL","side":"BUY","type":"LIMIT","price":180.50,
                  "quantity":100,"filledQty":100,"status":"FILLED",
                  "createdAt":"2026-04-26T10:00:00Z","updatedAt":"2026-04-26T10:00:01Z"}]
                """);
        stubOrders("u2", """
                [{"orderId":"00000000-0000-0000-0000-000000000a02","clientOrderId":"c2",
                  "symbol":"AAPL","side":"SELL","type":"LIMIT","price":180.50,
                  "quantity":100,"filledQty":50,"status":"PARTIAL",
                  "createdAt":"2026-04-26T09:59:00Z","updatedAt":"2026-04-26T10:00:01Z"}]
                """);
        stubFills("u1", "[]");
        stubFills("u2", "[]");

        var report = runner.run("test-run");
        var inv1 = byName(report, "buy-sell-net-zero");
        assertThat(inv1.passed()).isFalse();
        assertThat(inv1.diffs()).anySatisfy(d -> assertThat(d).contains("AAPL").contains("diff=50"));
    }

    @Test
    void filledOverQuantity_failsInvariant2WithOrderId() {
        stubOrders("u1", """
                [{"orderId":"00000000-0000-0000-0000-000000000a01","clientOrderId":"c1",
                  "symbol":"AAPL","side":"BUY","type":"LIMIT","price":180.50,
                  "quantity":100,"filledQty":150,"status":"FILLED",
                  "createdAt":"2026-04-26T10:00:00Z","updatedAt":"2026-04-26T10:00:01Z"}]
                """);
        stubOrders("u2", "[]");
        stubFills("u1", "[]");
        stubFills("u2", "[]");

        var report = runner.run("test-run");
        var inv2 = byName(report, "filled-le-quantity");
        assertThat(inv2.passed()).isFalse();
        assertThat(inv2.diffs()).anySatisfy(d -> assertThat(d)
                .contains("filledQty=150 > quantity=100")
                .contains("00000000-0000-0000-0000-000000000a01"));
    }

    @Test
    void tradeMissingCounterpart_failsInvariant3() {
        stubOrders("u1", "[]");
        stubOrders("u2", "[]");
        stubFills("u1", """
                [{"tradeId":"00000000-0000-0000-0000-00000000ad01","symbol":"AAPL",
                  "side":"BUY","price":180.50,"quantity":100,
                  "executedAt":"2026-04-26T10:00:01Z","counterparty":"u2"}]
                """);
        stubFills("u2", "[]");

        var report = runner.run("test-run");
        var inv3 = byName(report, "trade-counterparts");
        assertThat(inv3.passed()).isFalse();
        assertThat(inv3.diffs()).anySatisfy(d -> assertThat(d).contains("expected exactly 2 fills"));
    }

    @Test
    void counterpartyNameMismatch_failsInvariant3() {
        stubOrders("u1", "[]");
        stubOrders("u2", "[]");
        // BUY claims counterparty=u3 but SELL is on u2 — inconsistent.
        stubFills("u1", """
                [{"tradeId":"00000000-0000-0000-0000-00000000ad01","symbol":"AAPL",
                  "side":"BUY","price":180.50,"quantity":100,
                  "executedAt":"2026-04-26T10:00:01Z","counterparty":"u3"}]
                """);
        stubFills("u2", """
                [{"tradeId":"00000000-0000-0000-0000-00000000ad01","symbol":"AAPL",
                  "side":"SELL","price":180.50,"quantity":100,
                  "executedAt":"2026-04-26T10:00:01Z","counterparty":"u1"}]
                """);

        var report = runner.run("test-run");
        var inv3 = byName(report, "trade-counterparts");
        assertThat(inv3.passed()).isFalse();
        assertThat(inv3.diffs()).anySatisfy(d -> assertThat(d).contains("BUY-side counterparty=u3"));
    }

    // ---------- helpers ----------

    private void stubOrders(String user, String body) {
        wm.stubFor(get(urlEqualTo("/api/orders/mine"))
                .withHeader("Authorization", equalTo("Bearer jwt-" + user))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubFills(String user, String body) {
        wm.stubFor(get(urlEqualTo("/api/fills/mine"))
                .withHeader("Authorization", equalTo("Bearer jwt-" + user))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static AssertionResult byName(com.sweta.limitorder.simulator.report.RunReport report, String name) {
        return report.assertions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }
}
