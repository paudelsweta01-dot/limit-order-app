package com.sweta.limitorder.simulator.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.api.dto.OrderStatus;
import com.sweta.limitorder.simulator.api.dto.OrderType;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderResponse;

/**
 * Phase 2.1 / 2.3 acceptance: every method round-trips against the
 * §4.11 envelope on error, retries on 5xx with the same body, and
 * doesn't retry on 4xx.
 */
class LobApiClientTest {

    private WireMockServer wm;
    private LobApiClient api;
    private JwtToken token;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        api = new LobApiClient("http://localhost:" + wm.port());
        token = new JwtToken("fake-jwt", UUID.fromString("00000000-0000-0000-0000-000000000001"), "Alice");
    }

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
    }

    // ---------- happy paths ----------

    @Test
    void loginReturnsJwtToken() {
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "token": "abc.def.ghi",
                                  "userId": "00000000-0000-0000-0000-000000000001",
                                  "name": "Alice"
                                }
                                """)));
        JwtToken t = api.login("u1", "alice123");
        assertThat(t.token()).isEqualTo("abc.def.ghi");
        assertThat(t.name()).isEqualTo("Alice");
    }

    @Test
    void getBookReturnsSnapshot() {
        wm.stubFor(get(urlEqualTo("/api/book/AAPL"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"symbol":"AAPL","bids":[],"asks":[],"cursor":42}
                                """)));
        var book = api.getBook("AAPL", token);
        assertThat(book.symbol()).isEqualTo("AAPL");
        assertThat(book.cursor()).isEqualTo(42);
    }

    @Test
    void submitSendsBearerHeaderAndDeserialisesResponse() {
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderId": "00000000-0000-0000-0000-0000000000ab",
                                  "status": "OPEN",
                                  "filledQty": 0,
                                  "idempotentReplay": false
                                }
                                """)));
        SubmitOrderResponse res = api.submit(new SubmitOrderRequest(
                "01900000-0000-7000-8000-000000000001", "AAPL",
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("180.50"), 100), token);
        assertThat(res.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(res.idempotentReplay()).isFalse();

        var posts = wm.findAll(postRequestedFor(urlEqualTo("/api/orders")));
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).getHeader("Authorization")).isEqualTo("Bearer fake-jwt");
    }

    @Test
    void cancelHitsDeleteEndpoint() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        wm.stubFor(delete(urlEqualTo("/api/orders/" + orderId))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderId":"%s","status":"CANCELLED","filledQty":0}
                                """.formatted(orderId))));
        var res = api.cancel(orderId, token);
        assertThat(res.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    // ---------- error paths ----------

    @Test
    void fourOhOneOnLoginThrowsTypedExceptionWithEnvelope() {
        wm.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"UNAUTHORIZED","message":"invalid username or password"}
                                """)));
        assertThatThrownBy(() -> api.login("u1", "wrong"))
                .isInstanceOfSatisfying(LobApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.envelope().code()).isEqualTo("UNAUTHORIZED");
                    assertThat(e.envelope().message()).isEqualTo("invalid username or password");
                });
    }

    @Test
    void fourHundredOnSubmitThrowsTypedExceptionAndDoesNotRetry() {
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"VALIDATION_FAILED","message":"price required for LIMIT"}
                                """)));
        assertThatThrownBy(() -> api.submit(new SubmitOrderRequest(
                "01900000-0000-7000-8000-000000000001", "AAPL",
                OrderSide.BUY, OrderType.LIMIT, null, 100), token))
                .isInstanceOf(LobApiException.class);
        // 4xx must NOT retry — exactly one POST.
        assertThat(wm.findAll(postRequestedFor(urlEqualTo("/api/orders")))).hasSize(1);
    }

    @Test
    void nonJsonBody_5xxFromNginx_yieldsExceptionWithNullEnvelope() {
        wm.stubFor(get(urlEqualTo("/api/book/AAPL"))
                .willReturn(aResponse().withStatus(502)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>502 Bad Gateway</body></html>")));
        assertThatThrownBy(() -> api.getBook("AAPL", token))
                .isInstanceOfSatisfying(LobApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.envelope()).isNull(); // not a §4.11 envelope
                });
    }

    // ---------- retry (plan §2.3) ----------

    @Test
    void retriesOn503AndReturnsFinalSuccess_sameClientOrderIdReusedAcrossRetries() {
        String scenario = "retry503";
        String orderJson = """
                {"clientOrderId":"01900000-0000-7000-8000-000000000001","symbol":"AAPL",
                 "side":"BUY","type":"LIMIT","price":180.50,"quantity":100}
                """;

        wm.stubFor(post(urlEqualTo("/api/orders"))
                .inScenario(scenario).whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("first-retry"));
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .inScenario(scenario).whenScenarioStateIs("first-retry")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-retry"));
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .inScenario(scenario).whenScenarioStateIs("second-retry")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderId":"00000000-0000-0000-0000-0000000000ab",
                                 "status":"OPEN","filledQty":0,"idempotentReplay":false}
                                """)));

        SubmitOrderResponse res = api.submit(new SubmitOrderRequest(
                "01900000-0000-7000-8000-000000000001", "AAPL",
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("180.50"), 100), token);
        assertThat(res.status()).isEqualTo(OrderStatus.OPEN);

        // All three POSTs must carry the same clientOrderId — that's the
        // promise we make to the backend's idempotency layer (§4.6).
        var posts = wm.findAll(postRequestedFor(urlEqualTo("/api/orders")));
        assertThat(posts).hasSize(3);
        for (var post : posts) {
            assertThat(post.getBodyAsString())
                    .contains("\"clientOrderId\":\"01900000-0000-7000-8000-000000000001\"");
        }
    }

    @Test
    void givesUpAfterMaxAttemptsOnPersistent503() {
        wm.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> api.submit(new SubmitOrderRequest(
                "01900000-0000-7000-8000-000000000002", "AAPL",
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("180.50"), 100), token))
                .isInstanceOfSatisfying(LobApiException.class, e ->
                        assertThat(e.status()).isEqualTo(503));
        assertThat(wm.findAll(postRequestedFor(urlEqualTo("/api/orders"))))
                .hasSize(LobApiClient.MAX_ATTEMPTS);
    }
}
