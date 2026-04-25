package com.sweta.limitorder.api.book;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.auth.LoginRequest;
import com.sweta.limitorder.auth.LoginResponse;
import com.sweta.limitorder.book.BookLevel;
import com.sweta.limitorder.book.BookSnapshot;
import com.sweta.limitorder.book.BookTotals;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.1 / 6.2 / 6.3 — read-side API for the book.
 *
 * <p>State is set up via {@link MatchingEngineService} (cheap, in-process)
 * rather than by logging in as four users and POSTing through the HTTP layer.
 * Phase 5's {@code OrderControllerIntegrationTest} already covers the POST
 * surface end-to-end; here we focus on the read-side queries against a
 * known book state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class BookControllerIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private MatchingEngineService engine;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
        token = login("u1", "alice123");
    }

    // ---------- auth + symbol existence ----------

    @Test
    @SuppressWarnings("unchecked")
    void getBookWithoutAuthReturns401() {
        ResponseEntity<Map> r = rest.getForEntity("/api/book/AAPL", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getBookForUnknownSymbolReturns400() {
        ResponseEntity<Map> r = rest.exchange(
                "/api/book/ZZZZ", HttpMethod.GET, bearer(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTotalsForUnknownSymbolReturns400() {
        ResponseEntity<Map> r = rest.exchange(
                "/api/book/ZZZZ/totals", HttpMethod.GET, bearer(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- empty book ----------

    @Test
    void emptyBookSnapshotReturnsEmptyLevelsNullLastZeroCursor() {
        BookSnapshot s = getSnapshot("AAPL");
        assertThat(s.symbol()).isEqualTo("AAPL");
        assertThat(s.bids()).isEmpty();
        assertThat(s.asks()).isEmpty();
        assertThat(s.last()).isNull();
        assertThat(s.cursor()).isZero();
    }

    @Test
    void emptyBookTotalsReturnsZeroes() {
        BookTotals t = getTotals("AMZN");
        assertThat(t.demand()).isZero();
        assertThat(t.supply()).isZero();
    }

    // ---------- §5.3 seed → §5.4 expected state ----------

    @Test
    void aaplSnapshotAfterSeedMatchesSpec_5_4() throws Exception {
        replaySeedScenario();

        BookSnapshot s = getSnapshot("AAPL");

        // §5.4: BID = u4 180.00 x50 (1 user)
        assertThat(s.bids()).hasSize(1);
        BookLevel bid = s.bids().get(0);
        assertThat(bid.price()).isEqualByComparingTo("180.00");
        assertThat(bid.qty()).isEqualTo(50);
        assertThat(bid.userCount()).isEqualTo(1);

        // §5.4: ASK levels are 180.50 x80, 181.00 x100, 182.00 x150 (best first)
        assertThat(s.asks()).hasSize(3);
        assertThat(s.asks().get(0).price()).isEqualByComparingTo("180.50");
        assertThat(s.asks().get(0).qty()).isEqualTo(80);
        assertThat(s.asks().get(1).price()).isEqualByComparingTo("181.00");
        assertThat(s.asks().get(1).qty()).isEqualTo(100);
        assertThat(s.asks().get(2).price()).isEqualByComparingTo("182.00");
        assertThat(s.asks().get(2).qty()).isEqualTo(150);

        // Last trade was c005 ↔ c002 at 180.50
        assertThat(s.last()).isEqualByComparingTo("180.50");

        // Outbox emitted events during replay → cursor must be > 0
        assertThat(s.cursor()).isPositive();
    }

    @Test
    void msftSnapshotAfterSeedHasOnlyAskSideAndLastFromTrade() throws Exception {
        replaySeedScenario();

        BookSnapshot s = getSnapshot("MSFT");
        assertThat(s.bids()).isEmpty();
        assertThat(s.asks()).hasSize(1);
        assertThat(s.asks().get(0).price()).isEqualByComparingTo("421.00");
        assertThat(s.asks().get(0).qty()).isEqualTo(30);  // 80 - 50 (c007 hit)
        assertThat(s.last()).isEqualByComparingTo("421.00");  // resting price wins
    }

    @Test
    void googlSnapshotAfterSeedHasNoTradeSoLastIsNull() throws Exception {
        replaySeedScenario();

        BookSnapshot s = getSnapshot("GOOGL");
        assertThat(s.asks()).hasSize(1);
        assertThat(s.asks().get(0).qty()).isEqualTo(300);
        assertThat(s.last()).isNull();
    }

    @Test
    void totalsForAaplAfterSeedMatchMarketOverviewRow() throws Exception {
        replaySeedScenario();

        // §6.2 wireframe row: AAPL Demand=50, Supply=330
        BookTotals t = getTotals("AAPL");
        assertThat(t.demand()).isEqualTo(50);
        assertThat(t.supply()).isEqualTo(80 + 100 + 150);
    }

    @Test
    void totalsForMsftAfterSeedMatchMarketOverviewRow() throws Exception {
        replaySeedScenario();

        // §6.2 wireframe row: MSFT Demand=0, Supply=30
        BookTotals t = getTotals("MSFT");
        assertThat(t.demand()).isZero();
        assertThat(t.supply()).isEqualTo(30);
    }

    // ---------- price-level aggregation: multiple users at same price ----------

    @Test
    void multipleUsersAtSamePriceLevelAreAggregated() {
        // Two distinct users place SELLs at the same price.
        engine.submit(limit("u1-a", "u1", "AAPL", OrderSide.SELL, "180.00", 50));
        engine.submit(limit("u2-a", "u2", "AAPL", OrderSide.SELL, "180.00", 30));
        engine.submit(limit("u3-a", "u3", "AAPL", OrderSide.SELL, "180.00", 20));

        BookSnapshot s = getSnapshot("AAPL");
        assertThat(s.asks()).hasSize(1);
        BookLevel level = s.asks().get(0);
        assertThat(level.price()).isEqualByComparingTo("180.00");
        assertThat(level.qty()).isEqualTo(100);          // 50 + 30 + 20
        assertThat(level.userCount()).isEqualTo(3);      // distinct users
    }

    // ---------- helpers ----------

    private String login(String username, String password) {
        return Objects.requireNonNull(rest.postForObject(
                "/api/auth/login",
                new LoginRequest(username, password),
                LoginResponse.class)).token();
    }

    private BookSnapshot getSnapshot(String symbol) {
        return rest.exchange("/api/book/" + symbol, HttpMethod.GET,
                bearer(token), BookSnapshot.class).getBody();
    }

    private BookTotals getTotals(String symbol) {
        return rest.exchange("/api/book/" + symbol + "/totals", HttpMethod.GET,
                bearer(token), BookTotals.class).getBody();
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

    private void replaySeedScenario() throws Exception {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/scenarios/seed.csv"));
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine();  // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                engine.submit(new SubmitOrderCommand(
                        p[0],
                        seedUserId(p[1]),
                        p[2],
                        OrderSide.valueOf(p[3]),
                        OrderType.valueOf(p[4]),
                        p[5].isBlank() ? null : new BigDecimal(p[5]),
                        Long.parseLong(p[6])));
            }
        }
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
}
