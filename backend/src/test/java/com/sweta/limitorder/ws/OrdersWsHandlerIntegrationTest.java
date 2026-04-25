package com.sweta.limitorder.ws;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 8.4 — auth + snapshot + delta on the per-user orders channel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class OrdersWsHandlerIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MatchingEngineService engine;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InMemoryWsBroker broker;

    private String aliceToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
        aliceToken = login("u1", "alice123");
    }

    @Test
    void snapshotIncludesExistingOrdersForCaller() throws Exception {
        engine.submit(buy("c1", "u1", "AAPL", "175.00", 100));
        engine.submit(buy("c2", "u1", "MSFT", "420.00", 50));

        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession ignored = openWs(client, "/ws/orders/mine", aliceToken)) {
            String snapshot = client.poll(5);
            assertThat(snapshot).contains("\"type\":\"snapshot\"")
                    .contains("\"c1\"")
                    .contains("\"c2\"");
        }
    }

    @Test
    void deltaForwardedWhenCallersOrderStateChanges() throws Exception {
        // Bob (u2) places an ASK that Alice's incoming BUY will cross.
        engine.submit(sell("ask", "u2", "AAPL", "180.00", 100));

        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession ignored = openWs(client, "/ws/orders/mine", aliceToken)) {
            client.poll(5); // drain snapshot
            awaitSubscribed("orders:" + seedUserId("u1"));

            engine.submit(buy("buy", "u1", "AAPL", "180.00", 100));

            // Engine emits orders:u1 delta for the new order's status change.
            String delta = client.poll(5);
            assertThat(delta).contains("\"type\":\"delta\"")
                    .contains("\"orders:")
                    .contains("FILLED");
        }
    }

    @Test
    void deltaForOtherUsersOrdersIsNotForwardedToCaller() throws Exception {
        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession ignored = openWs(client, "/ws/orders/mine", aliceToken)) {
            client.poll(5); // drain snapshot
            awaitSubscribed("orders:" + seedUserId("u1"));

            // Bob and Charlie trade — Alice should see nothing on her channel.
            engine.submit(sell("a", "u2", "AAPL", "180.00", 50));
            engine.submit(buy("b",  "u3", "AAPL", "180.00", 50));

            String maybeStray = client.poll(2);
            assertThat(maybeStray)
                    .as("Alice's orders channel must not receive Bob/Charlie deltas")
                    .isNull();
        }
    }

    // ---------- helpers ----------

    private String login(String username, String password) {
        return Objects.requireNonNull(rest.postForObject(
                "/api/auth/login",
                new LoginRequest(username, password),
                LoginResponse.class)).token();
    }

    private WebSocketSession openWs(CapturingWsClient client, String path, String token) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        return new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + path))
                .get(5, TimeUnit.SECONDS);
    }

    private void awaitSubscribed(String channel) {
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(broker.subscriberCount(channel)).isGreaterThan(0));
    }

    private static SubmitOrderCommand buy(String clientId, String username, String symbol,
                                          String price, long qty) {
        return new SubmitOrderCommand(clientId, seedUserId(username), symbol,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static SubmitOrderCommand sell(String clientId, String username, String symbol,
                                           String price, long qty) {
        return new SubmitOrderCommand(clientId, seedUserId(username), symbol,
                OrderSide.SELL, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
}
