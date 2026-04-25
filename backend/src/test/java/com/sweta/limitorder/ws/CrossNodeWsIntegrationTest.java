package com.sweta.limitorder.ws;

import com.sweta.limitorder.LobApplication;
import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.api.orders.SubmitOrderRequest;
import com.sweta.limitorder.api.orders.SubmitOrderResponse;
import com.sweta.limitorder.auth.LoginRequest;
import com.sweta.limitorder.auth.LoginResponse;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 8.6 — the multi-instance NFR proof.
 *
 * <p>Spins up a SECOND application context on a different port that talks
 * to the same Postgres testcontainer the {@code @SpringBootTest} primary
 * is using. WS-connect to the primary, POST an order to the secondary,
 * assert the WS client receives a delta — proving NOTIFY crosses the
 * cluster boundary within the §3 NFR's 1-second budget.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossNodeWsIntegrationTest {

    @LocalServerPort private int primaryPort;
    @Autowired private TestRestTemplate primaryRest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcConnectionDetails primaryConnectionDetails;
    @Autowired private InMemoryWsBroker primaryBroker;

    private ConfigurableApplicationContext secondaryContext;
    private int secondaryPort;
    private final TestRestTemplate secondaryRest = new TestRestTemplate();

    @BeforeAll
    void startSecondaryInstance() {
        // Pass as --command-line args (highest precedence) so they override
        // the application.yml default for spring.datasource.url. Using
        // .properties() instead would set them at LOWEST precedence and
        // application.yml's default would win.
        secondaryContext = new SpringApplicationBuilder(LobApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url="      + primaryConnectionDetails.getJdbcUrl(),
                        "--spring.datasource.username=" + primaryConnectionDetails.getUsername(),
                        "--spring.datasource.password=" + primaryConnectionDetails.getPassword(),
                        "--app.instance-id=secondary",
                        "--logging.level.com.sweta.limitorder=WARN"
                );
        secondaryPort = Integer.parseInt(Objects.requireNonNull(
                secondaryContext.getEnvironment().getProperty("local.server.port")));
    }

    @AfterAll
    void stopSecondary() {
        if (secondaryContext != null) {
            secondaryContext.close();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
    }

    @Test
    void wsConnectedToPrimaryReceivesDeltaFromOrderSubmittedOnSecondary() throws Exception {
        String aliceToken = loginOn(primaryRest, primaryPort, "u1", "alice123");
        String bobToken   = loginOn(primaryRest, primaryPort, "u2", "bob123");

        // Pre-seed via PRIMARY: Bob places a SELL that Alice's incoming BUY
        // (submitted via SECONDARY) will cross. The engine emits outbox rows
        // only on a match, so a non-crossing submit would produce no delta.
        ResponseEntity<SubmitOrderResponse> seed = primaryRest.exchange(
                "http://localhost:" + primaryPort + "/api/orders",
                HttpMethod.POST,
                bearer(bobToken, new SubmitOrderRequest(
                        "seed-ask", "AAPL", OrderSide.SELL,
                        OrderType.LIMIT, new BigDecimal("180.00"), 100)),
                SubmitOrderResponse.class);
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 1. WS-connect to PRIMARY.
        CapturingWsClient client = new CapturingWsClient();
        WebSocketHttpHeaders wsHeaders = new WebSocketHttpHeaders();
        wsHeaders.setBearerAuth(aliceToken);
        try (WebSocketSession session = new StandardWebSocketClient()
                .execute(client, wsHeaders,
                        URI.create("ws://localhost:" + primaryPort + "/ws/book/AAPL"))
                .get(5, TimeUnit.SECONDS)) {

            // Drain the snapshot.
            String snapshot = client.poll(5);
            assertThat(snapshot).contains("\"type\":\"snapshot\"");

            // Wait until the WS handler on PRIMARY has finished registering
            // our subscription before triggering the secondary's submit.
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(primaryBroker.subscriberCount("book:AAPL")).isGreaterThan(0));

            // 2. POST a CROSSING order to the SECONDARY instance.
            long t0 = System.nanoTime();
            ResponseEntity<SubmitOrderResponse> submitResponse = secondaryRest.exchange(
                    "http://localhost:" + secondaryPort + "/api/orders",
                    HttpMethod.POST,
                    bearer(aliceToken, new SubmitOrderRequest(
                            "cross-node-buy", "AAPL", OrderSide.BUY,
                            OrderType.LIMIT, new BigDecimal("180.00"), 100)),
                    SubmitOrderResponse.class);
            assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // 3. The WS client on PRIMARY must see at least one delta. The §3 NFR
            //    is 1s; we give Awaitility headroom up to 5s for stability on
            //    cold containers, then assert the actual elapsed time was <1s.
            String delta = client.poll(5);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(delta)
                    .as("WS client on primary must receive a delta after submit on secondary")
                    .isNotNull()
                    .contains("\"type\":\"delta\"");

            assertThat(elapsedMs)
                    .as("cross-node delivery should be under §3 NFR (1s); was %d ms", elapsedMs)
                    .isLessThan(1000);

            assertThat(session.isOpen()).isTrue();
        }
    }

    // ---------- helpers ----------

    private String loginOn(TestRestTemplate rest, int port, String username, String password) {
        return Objects.requireNonNull(rest.postForObject(
                URI.create("http://localhost:" + port + "/api/auth/login"),
                new LoginRequest(username, password),
                LoginResponse.class)).token();
    }

    private static <T> HttpEntity<T> bearer(String token, T body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(body, h);
    }
}
