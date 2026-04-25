package com.sweta.limitorder.api.orders;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.auth.LoginRequest;
import com.sweta.limitorder.auth.LoginResponse;
import com.sweta.limitorder.persistence.OrderRepository;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderStatus;
import com.sweta.limitorder.persistence.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 — REST surface in front of the matching engine. Full end-to-end
 * runs against a Testcontainers Postgres + the live Spring Security chain
 * + the engine itself. Covers every status code the plan calls out
 * (201 / 200-replay / 400 / 401 / 403 / 404 / 409) plus the §6.4 ordering
 * requirement on /api/orders/mine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class OrderControllerIntegrationTest {

    private static final ParameterizedTypeReference<List<MyOrderResponse>> MY_ORDERS_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
        aliceToken = login("u1", "alice123");
        bobToken   = login("u2", "bob123");
    }

    // ---------- POST /api/orders ----------

    @Test
    @SuppressWarnings("unchecked")
    void postWithoutAuthReturns401() {
        SubmitOrderRequest req = limit("c1", "AAPL", OrderSide.BUY, "175.00", 100);
        ResponseEntity<Map> r = rest.postForEntity("/api/orders", req, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postValidLimitOrderReturns201Open() {
        SubmitOrderRequest req = limit("c1", "AAPL", OrderSide.BUY, "175.00", 100);
        ResponseEntity<SubmitOrderResponse> r = rest.exchange(
                "/api/orders", HttpMethod.POST,
                bearer(aliceToken, req), SubmitOrderResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().status()).isEqualTo(OrderStatus.OPEN);
        assertThat(r.getBody().filledQty()).isZero();
        assertThat(r.getBody().idempotentReplay()).isFalse();
        assertThat(r.getBody().orderId()).isNotNull();
    }

    @Test
    void marketAgainstEmptyBookReturns201CancelledWithReason() {
        SubmitOrderRequest req = market("m1", "AAPL", OrderSide.BUY, 100);
        ResponseEntity<SubmitOrderResponse> r = rest.exchange(
                "/api/orders", HttpMethod.POST,
                bearer(aliceToken, req), SubmitOrderResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(r.getBody().filledQty()).isZero();
        assertThat(r.getBody().rejectReason()).isEqualTo("INSUFFICIENT_LIQUIDITY");
    }

    // ---------- POST validation failures ----------

    @Test
    void postWithBlankClientOrderIdReturns400() {
        assertValidationFails(limit("", "AAPL", OrderSide.BUY, "175", 100));
    }

    @Test
    void postWithZeroQuantityReturns400() {
        assertValidationFails(limit("c1", "AAPL", OrderSide.BUY, "175", 0));
    }

    @Test
    void postWithUnknownSymbolReturns400() {
        SubmitOrderRequest req = limit("c1", "ZZZZ", OrderSide.BUY, "100", 100);
        ResponseEntity<Map> r = exchangeMap(HttpMethod.POST, "/api/orders", aliceToken, req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat((String) r.getBody().get("message")).contains("ZZZZ");
    }

    @Test
    void postLimitWithoutPriceReturns400() {
        SubmitOrderRequest req = new SubmitOrderRequest(
                "c1", "AAPL", OrderSide.BUY, OrderType.LIMIT, null, 100);
        ResponseEntity<Map> r = exchangeMap(HttpMethod.POST, "/api/orders", aliceToken, req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void postMarketWithPriceReturns400() {
        SubmitOrderRequest req = new SubmitOrderRequest(
                "c1", "AAPL", OrderSide.BUY, OrderType.MARKET, new BigDecimal("100"), 100);
        ResponseEntity<Map> r = exchangeMap(HttpMethod.POST, "/api/orders", aliceToken, req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    // ---------- Idempotent replay ----------

    @Test
    void duplicateClientOrderIdReturns200ReplayWithSameOrderId() {
        SubmitOrderRequest req = limit("c1", "AAPL", OrderSide.BUY, "175.00", 100);

        ResponseEntity<SubmitOrderResponse> first = rest.exchange(
                "/api/orders", HttpMethod.POST,
                bearer(aliceToken, req), SubmitOrderResponse.class);
        ResponseEntity<SubmitOrderResponse> second = rest.exchange(
                "/api/orders", HttpMethod.POST,
                bearer(aliceToken, req), SubmitOrderResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().idempotentReplay()).isFalse();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().idempotentReplay()).isTrue();
        assertThat(second.getBody().orderId()).isEqualTo(first.getBody().orderId());

        assertThat(orders.findAll()).hasSize(1);
    }

    // ---------- DELETE /api/orders/{id} ----------

    @Test
    void cancelOwnOpenOrderReturns200Cancelled() {
        UUID orderId = submit(aliceToken, limit("c1", "AAPL", OrderSide.BUY, "175.00", 100));

        ResponseEntity<CancelOrderResponse> r = rest.exchange(
                "/api/orders/" + orderId, HttpMethod.DELETE,
                bearer(aliceToken), CancelOrderResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(r.getBody().filledQty()).isZero();
    }

    @Test
    void cancelByNonOwnerReturns403() {
        UUID orderId = submit(aliceToken, limit("c1", "AAPL", OrderSide.BUY, "175.00", 100));

        ResponseEntity<Map> r = exchangeMap(HttpMethod.DELETE,
                "/api/orders/" + orderId, bobToken, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void cancelOfMissingOrderReturns404() {
        UUID missing = UUID.randomUUID();
        ResponseEntity<Map> r = exchangeMap(HttpMethod.DELETE,
                "/api/orders/" + missing, aliceToken, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void cancelOfFilledOrderReturns409() {
        // Bob places a SELL @ 180 x100; Alice's BUY @ 180 x100 fills it immediately.
        submit(bobToken,   limit("ask", "AAPL", OrderSide.SELL, "180.00", 100));
        UUID aliceBuy = submit(aliceToken, limit("buy", "AAPL", OrderSide.BUY, "180.00", 100));

        ResponseEntity<Map> r = exchangeMap(HttpMethod.DELETE,
                "/api/orders/" + aliceBuy, aliceToken, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_STATE");
    }

    // ---------- GET /api/orders/mine ----------

    @Test
    @SuppressWarnings("unchecked")
    void getMineWithoutAuthReturns401() {
        ResponseEntity<Map> r = rest.getForEntity("/api/orders/mine", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getMineReturnsOnlyOwnOrdersNewestFirst() throws Exception {
        // Alice submits 3, Bob submits 1.
        submit(aliceToken, limit("a1", "AAPL", OrderSide.BUY,  "175.00", 100));
        Thread.sleep(5); // ensure distinct created_at; Postgres' resolution is microsecond
        submit(aliceToken, limit("a2", "MSFT", OrderSide.SELL, "420.00", 50));
        submit(bobToken,   limit("b1", "AAPL", OrderSide.BUY,  "170.00", 200));
        Thread.sleep(5);
        submit(aliceToken, limit("a3", "TSLA", OrderSide.BUY,  "240.00", 25));

        ResponseEntity<List<MyOrderResponse>> r = rest.exchange(
                "/api/orders/mine", HttpMethod.GET, bearer(aliceToken), MY_ORDERS_LIST);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(3);

        // Newest first per architecture §6.4
        assertThat(r.getBody())
                .extracting(MyOrderResponse::clientOrderId)
                .containsExactly("a3", "a2", "a1");

        // No leakage of Bob's orders
        assertThat(r.getBody())
                .extracting(MyOrderResponse::clientOrderId)
                .doesNotContain("b1");
    }

    // ---------- helpers ----------

    private String login(String username, String password) {
        ResponseEntity<LoginResponse> r = rest.postForEntity(
                "/api/auth/login", new LoginRequest(username, password), LoginResponse.class);
        return r.getBody().token();
    }

    private static SubmitOrderRequest limit(String clientOrderId, String symbol,
                                            OrderSide side, String price, long qty) {
        return new SubmitOrderRequest(
                clientOrderId, symbol, side, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static SubmitOrderRequest market(String clientOrderId, String symbol,
                                             OrderSide side, long qty) {
        return new SubmitOrderRequest(
                clientOrderId, symbol, side, OrderType.MARKET, null, qty);
    }

    private UUID submit(String token, SubmitOrderRequest req) {
        ResponseEntity<SubmitOrderResponse> r = rest.exchange(
                "/api/orders", HttpMethod.POST,
                bearer(token, req), SubmitOrderResponse.class);
        return r.getBody().orderId();
    }

    private void assertValidationFails(SubmitOrderRequest req) {
        ResponseEntity<Map> r = exchangeMap(HttpMethod.POST, "/api/orders", aliceToken, req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    private static <T> HttpEntity<T> bearer(String token, T body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(body, h);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> exchangeMap(HttpMethod method, String url, String token, Object body) {
        HttpEntity<?> entity = (body == null) ? bearer(token) : bearer(token, body);
        return rest.exchange(url, method, entity, Map.class);
    }
}
