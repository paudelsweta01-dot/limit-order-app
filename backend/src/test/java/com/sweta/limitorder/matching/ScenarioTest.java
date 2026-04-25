package com.sweta.limitorder.matching;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.persistence.OrderRepository;
import com.sweta.limitorder.persistence.OrderRow;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderStatus;
import com.sweta.limitorder.persistence.OrderType;
import com.sweta.limitorder.persistence.TradeRepository;
import com.sweta.limitorder.persistence.TradeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5 — the keystone test.
 *
 * <p>Replays the spec §5.3 seed CSV through {@link MatchingEngineService#submit}
 * in order and asserts that every order ends in the §5.4 expected state and
 * that exactly the three expected trades fired (AAPL c005↔c002 at 180.50,
 * MSFT c007↔c006 at 421.00, TSLA c009↔c008 at 239.00).
 *
 * <p>This test is the project's "yes it works" signal — if it goes red, the
 * matching engine is wrong, full stop.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ScenarioTest {

    @Autowired private MatchingEngineService engine;
    @Autowired private OrderRepository orders;
    @Autowired private TradeRepository trades;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
    }

    @Test
    void seedScenarioProducesExpectedSnapshotAndTrades() throws Exception {
        replayScenarioCsv("/scenarios/seed.csv");

        // -------- per-order final states (architecture §5.3 / §5.4) --------
        assertOrderFinalState("c001", u1(), OrderStatus.OPEN,    0);
        assertOrderFinalState("c002", u2(), OrderStatus.PARTIAL, 120);  // 200 - 120 = 80 remaining
        assertOrderFinalState("c003", u3(), OrderStatus.OPEN,    0);
        assertOrderFinalState("c004", u4(), OrderStatus.OPEN,    0);
        assertOrderFinalState("c005", u1(), OrderStatus.FILLED,  120);
        assertOrderFinalState("c006", u2(), OrderStatus.PARTIAL, 50);   // 80 - 50 = 30 remaining
        assertOrderFinalState("c007", u3(), OrderStatus.FILLED,  50);
        assertOrderFinalState("c008", u4(), OrderStatus.PARTIAL, 100);  // 200 - 100 = 100 remaining
        assertOrderFinalState("c009", u1(), OrderStatus.FILLED,  100);
        assertOrderFinalState("c010", u2(), OrderStatus.OPEN,    0);

        // -------- trades (one per symbol that crossed) --------
        List<TradeRow> aapl  = trades.findBySymbol("AAPL");
        List<TradeRow> msft  = trades.findBySymbol("MSFT");
        List<TradeRow> tsla  = trades.findBySymbol("TSLA");
        List<TradeRow> googl = trades.findBySymbol("GOOGL");

        assertThat(aapl).hasSize(1);
        TradeRow aaplTrade = aapl.get(0);
        assertThat(aaplTrade.price()).isEqualByComparingTo("180.50");
        assertThat(aaplTrade.quantity()).isEqualTo(120);
        assertThat(aaplTrade.buyUserId()).isEqualTo(u1());      // c005
        assertThat(aaplTrade.sellUserId()).isEqualTo(u2());     // c002 (resting)

        assertThat(msft).hasSize(1);
        TradeRow msftTrade = msft.get(0);
        assertThat(msftTrade.price()).isEqualByComparingTo("421.00");  // resting price wins
        assertThat(msftTrade.quantity()).isEqualTo(50);
        assertThat(msftTrade.buyUserId()).isEqualTo(u3());      // c007
        assertThat(msftTrade.sellUserId()).isEqualTo(u2());     // c006

        assertThat(tsla).hasSize(1);
        TradeRow tslaTrade = tsla.get(0);
        assertThat(tslaTrade.price()).isEqualByComparingTo("239.00");
        assertThat(tslaTrade.quantity()).isEqualTo(100);
        assertThat(tslaTrade.buyUserId()).isEqualTo(u1());      // c009 MARKET BUY
        assertThat(tslaTrade.sellUserId()).isEqualTo(u4());     // c008

        assertThat(googl).isEmpty();

        // -------- §4.3 consistency invariants --------
        long buyFilled  = orders.findAll().stream()
                .filter(o -> o.side() == OrderSide.BUY)
                .mapToLong(OrderRow::filledQty).sum();
        long sellFilled = orders.findAll().stream()
                .filter(o -> o.side() == OrderSide.SELL)
                .mapToLong(OrderRow::filledQty).sum();
        assertThat(buyFilled)
                .as("Σ filled_qty(BUY) must equal Σ filled_qty(SELL)")
                .isEqualTo(sellFilled)
                .isEqualTo(120 + 50 + 100);

        assertThat(orders.findAll())
                .as("no order may have filled_qty > quantity")
                .allSatisfy(o -> assertThat(o.filledQty()).isLessThanOrEqualTo(o.quantity()));
    }

    // ---------- helpers ----------

    private void replayScenarioCsv(String classpathResource) throws Exception {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream(classpathResource));
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                String clientOrderId = parts[0];
                UUID userId          = seedUserId(parts[1]);
                String symbol        = parts[2];
                OrderSide side       = OrderSide.valueOf(parts[3]);
                OrderType type       = OrderType.valueOf(parts[4]);
                BigDecimal price     = parts[5].isBlank() ? null : new BigDecimal(parts[5]);
                long quantity        = Long.parseLong(parts[6]);

                engine.submit(new SubmitOrderCommand(
                        clientOrderId, userId, symbol, side, type, price, quantity));
            }
        }
    }

    private void assertOrderFinalState(String clientId, UUID userId,
                                       OrderStatus expectedStatus, long expectedFilledQty) {
        OrderRow row = orders.findByClientOrderId(userId, clientId)
                .orElseThrow(() -> new AssertionError("order not found: " + clientId));
        assertThat(row.status())
                .as("status of %s", clientId).isEqualTo(expectedStatus);
        assertThat(row.filledQty())
                .as("filled_qty of %s", clientId).isEqualTo(expectedFilledQty);
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
    private static UUID u1() { return seedUserId("u1"); }
    private static UUID u2() { return seedUserId("u2"); }
    private static UUID u3() { return seedUserId("u3"); }
    private static UUID u4() { return seedUserId("u4"); }
}
