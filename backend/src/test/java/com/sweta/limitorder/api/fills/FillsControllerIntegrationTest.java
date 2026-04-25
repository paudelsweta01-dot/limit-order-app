package com.sweta.limitorder.api.fills;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.auth.LoginRequest;
import com.sweta.limitorder.auth.LoginResponse;
import com.sweta.limitorder.matching.MatchingEngineService;
import com.sweta.limitorder.matching.SubmitOrderCommand;
import com.sweta.limitorder.persistence.OrderSide;
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
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.5 — /api/fills/mine with side + counterparty derivation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class FillsControllerIntegrationTest {

    private static final ParameterizedTypeReference<List<MyFillResponse>> FILLS_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private TestRestTemplate rest;
    @Autowired private MatchingEngineService engine;
    @Autowired private JdbcTemplate jdbc;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
        aliceToken = login("u1", "alice123");
        bobToken   = login("u2", "bob123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMineWithoutAuthReturns401() {
        ResponseEntity<Map> r = rest.getForEntity("/api/fills/mine", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void emptyFillsReturnsEmptyList() {
        ResponseEntity<List<MyFillResponse>> r = rest.exchange(
                "/api/fills/mine", HttpMethod.GET, bearer(aliceToken), FILLS_LIST);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEmpty();
    }

    @Test
    void aliceBuysFromBobThenBothSeeMatchingFillsWithCorrectSidesAndCounterparties() {
        // Bob places a SELL, Alice places a matching BUY → one trade.
        engine.submit(limit("ask", "u2", "AAPL", OrderSide.SELL, "180.00", 100));
        engine.submit(limit("buy", "u1", "AAPL", OrderSide.BUY,  "180.00", 100));

        // Alice's perspective: she BOUGHT, counterparty is u2 (Bob's username).
        List<MyFillResponse> aliceFills = fetchFills(aliceToken);
        assertThat(aliceFills).hasSize(1);
        MyFillResponse aliceFill = aliceFills.get(0);
        assertThat(aliceFill.side()).isEqualTo(OrderSide.BUY);
        assertThat(aliceFill.symbol()).isEqualTo("AAPL");
        assertThat(aliceFill.price()).isEqualByComparingTo("180.00");
        assertThat(aliceFill.quantity()).isEqualTo(100);
        assertThat(aliceFill.counterparty()).isEqualTo("u2");
        assertThat(aliceFill.executedAt()).isNotNull();
        assertThat(aliceFill.tradeId()).isNotNull();

        // Bob's perspective: he SOLD, counterparty is u1 (Alice).
        List<MyFillResponse> bobFills = fetchFills(bobToken);
        assertThat(bobFills).hasSize(1);
        MyFillResponse bobFill = bobFills.get(0);
        assertThat(bobFill.side()).isEqualTo(OrderSide.SELL);
        assertThat(bobFill.counterparty()).isEqualTo("u1");
        assertThat(bobFill.tradeId()).isEqualTo(aliceFill.tradeId());
    }

    @Test
    void multipleTradesAcrossSymbolsArrivedNewestFirst() throws Exception {
        // Trade #1: AAPL, Alice BUYS from Bob.
        engine.submit(limit("a-ask", "u2", "AAPL", OrderSide.SELL, "180.00", 100));
        engine.submit(limit("a-buy", "u1", "AAPL", OrderSide.BUY,  "180.00", 100));
        Thread.sleep(5);  // ensure distinct executed_at timestamps

        // Trade #2: TSLA, Alice SELLS to Bob.
        engine.submit(limit("t-bid", "u2", "TSLA", OrderSide.BUY,  "240.00", 50));
        engine.submit(limit("t-ask", "u1", "TSLA", OrderSide.SELL, "240.00", 50));

        List<MyFillResponse> aliceFills = fetchFills(aliceToken);
        assertThat(aliceFills).hasSize(2);

        // Newest first
        assertThat(aliceFills.get(0).symbol()).isEqualTo("TSLA");
        assertThat(aliceFills.get(0).side()).isEqualTo(OrderSide.SELL);
        assertThat(aliceFills.get(0).counterparty()).isEqualTo("u2");

        assertThat(aliceFills.get(1).symbol()).isEqualTo("AAPL");
        assertThat(aliceFills.get(1).side()).isEqualTo(OrderSide.BUY);
        assertThat(aliceFills.get(1).counterparty()).isEqualTo("u2");
    }

    @Test
    void fillsAreScopedToTheAuthenticatedUser() {
        // u3 ↔ u4 trade — neither u1 (Alice) nor u2 (Bob) participated.
        engine.submit(limit("ask", "u3", "AAPL", OrderSide.SELL, "180.00", 50));
        engine.submit(limit("buy", "u4", "AAPL", OrderSide.BUY,  "180.00", 50));

        // Alice and Bob both have empty fills.
        assertThat(fetchFills(aliceToken)).isEmpty();
        assertThat(fetchFills(bobToken)).isEmpty();
    }

    // ---------- helpers ----------

    private String login(String username, String password) {
        return Objects.requireNonNull(rest.postForObject(
                "/api/auth/login",
                new LoginRequest(username, password),
                LoginResponse.class)).token();
    }

    private List<MyFillResponse> fetchFills(String token) {
        return rest.exchange("/api/fills/mine", HttpMethod.GET, bearer(token), FILLS_LIST).getBody();
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private static SubmitOrderCommand limit(String clientId, String username, String symbol,
                                            OrderSide side, String price, long qty) {
        return new SubmitOrderCommand(clientId, seedUserId(username), symbol,
                side, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
}
