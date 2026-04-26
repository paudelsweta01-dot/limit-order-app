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
 * Plan §5.1 — short-duration smoke that proves the load loop spawns
 * threads, logs in, submits, and stops on duration elapse. Real load
 * tests run against the docker-compose stack from infra Phase 5.
 */
class LoadRunnerTest {

    private WireMockServer wm;
    private LoadRunner runner;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
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
                                [{"symbol":"AAPL","name":"Apple","refPrice":180.00},
                                 {"symbol":"MSFT","name":"Microsoft","refPrice":420.00}]
                                """)));
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderId":"00000000-0000-0000-0000-0000000000ab",
                                 "status":"OPEN","filledQty":0,"idempotentReplay":false}
                                """)));
        runner = new LoadRunner(
                new LobApiClient("http://localhost:" + wm.port()),
                new TokenCache(), SeedCredentials.defaults(),
                List.of("u1", "u2"),
                /* users */ 2, /* rate */ 50,
                /* duration */ Duration.ofSeconds(1),
                /* seed */ 42L);
    }

    @AfterEach
    void tearDown() { if (wm != null) wm.stop(); }

    @Test
    void shortRunSubmitsOrders_andStopsOnDurationElapse() {
        var report = runner.run("test-run");
        assertThat(report.submitted).isPositive();
        assertThat(report.accepted).isEqualTo(report.submitted);
        // Duration was 1s; allow 3s slack for thread shutdown + WireMock latency.
        assertThat(report.duration().toMillis()).isLessThan(3_500);
        assertThat(report.assertions).extracting(a -> a.name())
                .contains("load:duration-elapsed");
    }

    @Test
    void bootstrapFailureSurfacesAsAssertionFailure() {
        wm.resetMappings();
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"token":"jwt","userId":"00000000-0000-0000-0000-000000000001","name":"X"}
                                """)));
        wm.stubFor(get(urlEqualTo("/api/symbols"))
                .willReturn(aResponse().withStatus(500)
                        .withBody("oops")));

        var report = runner.run("test-run");
        assertThat(report.allAssertionsPassed()).isFalse();
        assertThat(report.assertions).extracting(a -> a.name())
                .contains("load:bootstrap");
    }
}
