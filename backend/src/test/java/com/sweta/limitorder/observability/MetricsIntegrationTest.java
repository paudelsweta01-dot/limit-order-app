package com.sweta.limitorder.observability;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.matching.MatchingEngineService;
import com.sweta.limitorder.matching.SubmitOrderCommand;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9.2 — Micrometer counters / timer fire on the right paths and the
 * values are correct. Endpoint exposure via /actuator/prometheus is a
 * separate concern (the registration is finicky in this Spring Boot 3.3
 * + Micrometer 1.13 + Prometheus client 1.x combo); deferred to deployment-
 * time validation against the docker-compose stack.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MetricsIntegrationTest {

    @Autowired private MatchingEngineService engine;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
    }

    // ---------- direct meter-registry assertions ----------
    //
    // The /actuator/prometheus scrape endpoint registration with Spring Boot
    // 3.3.5 + Micrometer 1.13.6 + Prometheus client 1.x is a known finicky
    // combo (the endpoint bean does not register reliably; we reproduced the
    // Phase 1 behaviour). This test asserts the metrics MACHINERY — the
    // counters/timer fire and the values are correct — through the
    // MeterRegistry directly. Endpoint exposure is documented as deferred
    // and will be re-validated at deployment time when running against a
    // real `docker compose up` rather than a Testcontainers slice.

    @Test
    void ordersReceivedCounterIncrementsPerSubmission() {
        Counter aaplLimit = meters.counter("orders_received_total",
                "type", "LIMIT", "symbol", "AAPL");
        double before = aaplLimit.count();

        engine.submit(buy("c1", "u1", "AAPL", "175.00", 100));
        engine.submit(buy("c2", "u1", "AAPL", "176.00", 50));

        assertThat(aaplLimit.count() - before).isEqualTo(2.0);
    }

    @Test
    void tradesExecutedCounterIncrementsPerMatch() {
        Counter aaplTrades = meters.counter("trades_executed_total", "symbol", "AAPL");
        double before = aaplTrades.count();

        engine.submit(sell("ask", "u2", "AAPL", "180.00", 100));   // resting
        engine.submit(buy("buy", "u1", "AAPL", "180.00", 100));    // crosses → 1 trade

        assertThat(aaplTrades.count() - before).isEqualTo(1.0);
    }

    @Test
    void ordersRejectedCounterIncrementsForInsufficientLiquidity() {
        Counter rejected = meters.counter("orders_rejected_total",
                "reason", MatchingEngineService.INSUFFICIENT_LIQUIDITY);
        double before = rejected.count();

        engine.submit(market("m1", "u1", "AAPL", OrderSide.BUY, 100));  // empty book

        assertThat(rejected.count() - before).isEqualTo(1.0);
    }

    @Test
    void matchDurationTimerRecordsEverySubmission() {
        Timer aaplTimer = meters.timer("match_duration_seconds", "symbol", "AAPL");
        long before = aaplTimer.count();

        engine.submit(buy("c1", "u1", "AAPL", "175.00", 100));
        engine.submit(buy("c2", "u1", "AAPL", "176.00", 50));

        assertThat(aaplTimer.count()).isEqualTo(before + 2);
    }

    @Test
    void outboxPublishedCounterIncrementsForEveryOutboxRow() {
        Counter outbox = meters.counter("outbox_published_total");
        double before = outbox.count();

        // Cross a trade — engine emits 4 outbox events
        // (book:AAPL, trades:AAPL, orders:u1, orders:u2).
        engine.submit(sell("ask", "u2", "AAPL", "180.00", 100));
        engine.submit(buy("buy", "u1", "AAPL", "180.00", 100));

        assertThat(outbox.count() - before).isEqualTo(4.0);
    }

    // ---------- helpers ----------

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

    private static SubmitOrderCommand market(String clientId, String username, String symbol,
                                             OrderSide side, long qty) {
        return new SubmitOrderCommand(clientId, seedUserId(username), symbol,
                side, OrderType.MARKET, null, qty);
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
}
