package com.sweta.limitorder.simulator.mode;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;

/**
 * Plan §6.3 acceptance — clean runs report books-equal-across-nodes
 * PASS; injected divergence on node B reports a clear diff.
 */
class MultiInstanceRunnerTest {

    private WireMockServer wmA;
    private WireMockServer wmB;
    private MultiInstanceRunner runner;

    @BeforeEach
    void setUp() {
        wmA = freshServer();
        wmB = freshServer();
        runner = new MultiInstanceRunner(
                new LobApiClient("http://localhost:" + wmA.port()),
                new LobApiClient("http://localhost:" + wmB.port()),
                new TokenCache(), SeedCredentials.defaults(),
                List.of("u1", "u2"),
                /* users */ 2, /* rate */ 50,
                /* duration */ Duration.ofMillis(500),
                /* seed */ 42L);
    }

    @AfterEach
    void tearDown() {
        if (wmA != null) wmA.stop();
        if (wmB != null) wmB.stop();
    }

    @Test
    void cleanRun_booksEqualAcrossNodes_PASS() {
        // Both nodes return identical books — the convergence assertion
        // should pass.
        String book = """
                {"symbol":"AAPL","bids":[],"asks":[
                  {"price":180.50,"qty":100,"userCount":1}
                ],"cursor":7}
                """;
        stubBook(wmA, "AAPL", book);
        stubBook(wmB, "AAPL", book);

        var report = runner.run("test-run");
        var conv = report.assertions.stream()
                .filter(a -> a.name().equals("books-equal-across-nodes")).findFirst().orElseThrow();
        assertThat(conv.passed())
                .as("expected books-equal PASS; diffs=%s", conv.diffs())
                .isTrue();
    }

    @Test
    void divergenceOnNodeB_failsWithClearDiff() {
        // Node A's book has qty=100, Node B's qty=200 — divergence.
        stubBook(wmA, "AAPL", """
                {"symbol":"AAPL","bids":[],"asks":[
                  {"price":180.50,"qty":100,"userCount":1}
                ],"cursor":7}
                """);
        stubBook(wmB, "AAPL", """
                {"symbol":"AAPL","bids":[],"asks":[
                  {"price":180.50,"qty":200,"userCount":1}
                ],"cursor":7}
                """);

        var report = runner.run("test-run");
        var conv = report.assertions.stream()
                .filter(a -> a.name().equals("books-equal-across-nodes")).findFirst().orElseThrow();
        assertThat(conv.passed()).isFalse();
        assertThat(conv.diffs()).anySatisfy(d -> assertThat(d)
                .contains("AAPL.asks[0]")
                .contains("nodeA=price=180.50 qty=100")
                .contains("nodeB=price=180.50 qty=200"));
    }

    @Test
    void unreachableNodeB_failsBootstrap() {
        // Stop node B mid-setup so its endpoints are gone.
        wmB.stop();
        wmB = null;

        var report = runner.run("test-run");
        assertThat(report.allAssertionsPassed()).isFalse();
        assertThat(report.assertions).extracting(a -> a.name())
                .contains("multi-instance:bootstrap");
    }

    // ---------- helpers ----------

    private static WireMockServer freshServer() {
        WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"token":"jwt","userId":"00000000-0000-0000-0000-000000000001","name":"X"}
                                """)));
        wm.stubFor(get(urlEqualTo("/api/symbols"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"symbol":"AAPL","name":"Apple","refPrice":180.00}]
                                """)));
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderId":"00000000-0000-0000-0000-0000000000ab",
                                 "status":"OPEN","filledQty":0,"idempotentReplay":false}
                                """)));
        return wm;
    }

    private static void stubBook(WireMockServer wm, String symbol, String body) {
        wm.stubFor(get(urlEqualTo("/api/book/" + symbol))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }
}
