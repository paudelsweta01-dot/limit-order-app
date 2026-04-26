package com.sweta.limitorder.simulator.mode;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;

/**
 * Plan §3 acceptance — replay the §5.3 seed CSV against a stubbed
 * backend, then check that the comparator green-lights a §5.4-shape
 * book and red-lights a corrupted one with a useful diff.
 */
class ScenarioRunnerTest {

    private WireMockServer wm;
    private ScenarioRunner runner;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        // Every login returns a stable token (the test only cares the
        // header is on outgoing submits, which LobApiClientTest already
        // verified — here we just need the runner to get *some* token).
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"token":"jwt","userId":"00000000-0000-0000-0000-000000000001","name":"X"}
                                """)));
        // Every submit accepts.
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderId":"00000000-0000-0000-0000-0000000000ab",
                                 "status":"OPEN","filledQty":0,"idempotentReplay":false}
                                """)));
        runner = new ScenarioRunner(
                new LobApiClient("http://localhost:" + wm.port()),
                new TokenCache(),
                SeedCredentials.defaults());
    }

    @AfterEach
    void tearDown() { if (wm != null) wm.stop(); }

    @Test
    void replaysAllRowsAndCountsSubmissions() throws IOException {
        var report = runner.run(Path.of("../docs/requirnments/seed.csv"), null, "test-run");
        assertThat(report.submitted).isEqualTo(10);
        assertThat(report.accepted).isEqualTo(10);
        assertThat(report.rejected).isZero();
        assertThat(report.orders).hasSize(10);
    }

    @Test
    void reportsFailedSubmissionsAsRejectedWithEnvelopeMessage(@TempDir Path tmp) throws IOException {
        // Stub /api/orders to 400 instead of 201 for this test.
        wm.resetMappings();
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"jwt\",\"userId\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"X\"}")));
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"VALIDATION_FAILED","message":"unknown symbol: ZZZZ"}
                                """)));
        Path csv = tmp.resolve("bad.csv");
        Files.writeString(csv, """
                clientOrderId,userId,symbol,side,type,price,quantity
                c001,u1,ZZZZ,BUY,LIMIT,1.00,1
                """);

        var report = runner.run(csv, null, "test-run");
        assertThat(report.rejected).isEqualTo(1);
        assertThat(report.accepted).isZero();
        var rec = report.orders.get(0);
        assertThat(rec.status()).isEqualTo("ERROR");
        assertThat(rec.error()).contains("unknown symbol");
    }

    @Test
    void bookComparator_passesOnExactMatch(@TempDir Path tmp) throws IOException {
        // Stub getBook(AAPL) returning the §5.4 expected shape.
        wm.stubFor(get(urlPathMatching("/api/book/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"symbol":"AAPL",
                                 "bids":[{"price":180.00,"qty":50,"userCount":1}],
                                 "asks":[{"price":180.50,"qty":80,"userCount":1}],
                                 "cursor":7}
                                """)));
        Path csv = tmp.resolve("one.csv");
        Files.writeString(csv, """
                clientOrderId,userId,symbol,side,type,price,quantity
                c001,u1,AAPL,BUY,LIMIT,180.00,50
                """);
        Path expected = tmp.resolve("exp.json");
        Files.writeString(expected, """
                {"AAPL":{
                  "bids":[{"price":"180.00","qty":50,"userCount":1}],
                  "asks":[{"price":"180.50","qty":80,"userCount":1}]
                }}
                """);

        var report = runner.run(csv, expected, "test-run");
        assertThat(report.allAssertionsPassed()).isTrue();
        assertThat(report.assertions).hasSize(1);
        assertThat(report.assertions.get(0).name()).isEqualTo("book:AAPL");
    }

    @Test
    void bookComparator_failsWithUsefulDiffOnMismatch(@TempDir Path tmp) throws IOException {
        // getBook returns qty=200 instead of expected qty=80.
        wm.stubFor(get(urlPathMatching("/api/book/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"symbol":"AAPL",
                                 "bids":[{"price":180.00,"qty":50,"userCount":1}],
                                 "asks":[{"price":180.50,"qty":200,"userCount":1}],
                                 "cursor":7}
                                """)));
        Path csv = tmp.resolve("one.csv");
        Files.writeString(csv, """
                clientOrderId,userId,symbol,side,type,price,quantity
                c001,u1,AAPL,BUY,LIMIT,180.00,50
                """);
        Path expected = tmp.resolve("exp.json");
        Files.writeString(expected, """
                {"AAPL":{
                  "bids":[{"price":"180.00","qty":50,"userCount":1}],
                  "asks":[{"price":"180.50","qty":80,"userCount":1}]
                }}
                """);

        var report = runner.run(csv, expected, "test-run");
        assertThat(report.allAssertionsPassed()).isFalse();
        var diffs = report.assertions.get(0).diffs();
        assertThat(diffs).anySatisfy(d -> assertThat(d)
                .contains("AAPL.asks[0]")
                .contains("expected price=180.50 qty=80")
                .contains("got price=180.50 qty=200"));
    }
}
