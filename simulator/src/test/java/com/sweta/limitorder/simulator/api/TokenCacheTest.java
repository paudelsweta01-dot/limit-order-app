package com.sweta.limitorder.simulator.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/** Plan §2.2 — per-user JWT cached for the run, refreshed on invalidate. */
class TokenCacheTest {

    private WireMockServer wm;
    private LobApiClient api;
    private TokenCache cache;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"token":"jwt-1","userId":"00000000-0000-0000-0000-000000000001","name":"Alice"}
                                """)));
        api = new LobApiClient("http://localhost:" + wm.port());
        cache = new TokenCache();
    }

    @AfterEach
    void tearDown() { if (wm != null) wm.stop(); }

    @Test
    void getOrLogin_secondCallHitsCache_oneHttpRequestTotal() {
        JwtToken first = cache.getOrLogin("u1", "alice123", api);
        JwtToken second = cache.getOrLogin("u1", "alice123", api);
        assertThat(second).isSameAs(first);
        assertThat(wm.findAll(postRequestedFor(urlEqualTo("/api/auth/login")))).hasSize(1);
    }

    @Test
    void invalidate_forcesNextCallToRequestFreshToken() {
        cache.getOrLogin("u1", "alice123", api);
        cache.invalidate("u1");
        assertThat(cache.has("u1")).isFalse();
        cache.getOrLogin("u1", "alice123", api);
        assertThat(wm.findAll(postRequestedFor(urlEqualTo("/api/auth/login")))).hasSize(2);
    }
}
