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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Phase 8.1 + 8.3 — auth at the WS handshake, snapshot frame on connect,
 * delta frames driven by the engine via the outbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class BookWsHandlerIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MatchingEngineService engine;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InMemoryWsBroker broker;

    private String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
        token = login("u1", "alice123");
    }

    @Test
    void connectingWithoutTokenIsRejectedAtHandshake() {
        CapturingWsClient client = new CapturingWsClient();
        StandardWebSocketClient ws = new StandardWebSocketClient();

        // No Authorization header, no ?token= query param → JwtAuthFilter doesn't
        // populate the SecurityContext → the AuthenticationEntryPoint returns 401
        // and the upgrade fails.
        assertThatThrownBy(() -> ws.execute(client, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/book/AAPL"))
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void snapshotFrameDeliveredImmediatelyOnConnect() throws Exception {
        // Pre-seed a couple of resting orders so the snapshot has something to show.
        engine.submit(buy("c1", "u1", "AAPL", "175.00", 100));
        engine.submit(sell("c2", "u2", "AAPL", "185.00", 50));

        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession session = openWs(client, "/ws/book/AAPL")) {
            String snapshot = client.poll(5);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot).contains("\"type\":\"snapshot\"");
            assertThat(snapshot).contains("\"channel\":\"book:AAPL\"");
            assertThat(snapshot).contains("\"cursor\"");
            // Snapshot payload includes the two resting orders we just submitted.
            // NUMERIC(18,4) serialises BigDecimal at scale 4, so prices come out as 175.0000 / 185.0000.
            assertThat(snapshot).contains("175.0000").contains("185.0000");
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    void deltaFrameForwardedWhenOutboxFiresOnSameSymbol() throws Exception {
        // Pre-seed an opposing order — the engine emits outbox rows only when
        // a match happens, so we need a crossing submit to drive a delta.
        engine.submit(sell("ask", "u2", "AAPL", "180.00", 50));

        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession ignored = openWs(client, "/ws/book/AAPL")) {
            String snapshot = client.poll(5);
            assertThat(snapshot).contains("\"type\":\"snapshot\"");

            // Server-side: BookWsHandler subscribes AFTER sending the snapshot.
            // From the test's perspective, snapshot poll() returning is necessary
            // but not sufficient — wait until the broker actually has us
            // registered before triggering the engine.
            awaitSubscribed("book:AAPL");

            engine.submit(buy("c1", "u1", "AAPL", "180.00", 50));

            String delta = client.poll(5);
            assertThat(delta).isNotNull();
            assertThat(delta).contains("\"type\":\"delta\"");
            assertThat(delta).contains("\"cursor\":");
        }
    }

    @Test
    void crossingTradeProducesBothBookAndTradesDeltas() throws Exception {
        engine.submit(sell("ask", "u2", "AAPL", "180.00", 100));

        CapturingWsClient client = new CapturingWsClient();
        try (WebSocketSession ignored = openWs(client, "/ws/book/AAPL")) {
            client.poll(5); // drain snapshot
            awaitSubscribed("book:AAPL");
            awaitSubscribed("trades:AAPL");

            engine.submit(buy("buy", "u1", "AAPL", "180.00", 100));

            // Engine emits 4 outbox events: book:AAPL, trades:AAPL, orders:u1, orders:u2.
            // We're subscribed to book:AAPL + trades:AAPL → expect 2 deltas.
            String first  = client.poll(5);
            String second = client.poll(5);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat((first + second))
                    .contains("\"channel\":\"book:AAPL\"")
                    .contains("\"channel\":\"trades:AAPL\"");
        }
    }

    @Test
    void unknownSymbolClosesConnectionWithBadDataStatus() throws Exception {
        CapturingWsClient client = new CapturingWsClient();
        WebSocketSession session = openWs(client, "/ws/book/ZZZZ");
        // Server closes immediately after handshake — the client may observe
        // the close before any frame arrives.
        Thread.sleep(500);
        assertThat(session.isOpen()).isFalse();
    }

    // ---------- helpers ----------

    private String login(String username, String password) {
        return Objects.requireNonNull(rest.postForObject(
                "/api/auth/login",
                new LoginRequest(username, password),
                LoginResponse.class)).token();
    }

    private WebSocketSession openWs(CapturingWsClient client, String path)
            throws InterruptedException, ExecutionException, TimeoutException {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        return new StandardWebSocketClient()
                .execute(client, headers,
                        URI.create("ws://localhost:" + port + path))
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
