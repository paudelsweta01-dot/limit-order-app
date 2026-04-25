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
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3.6 — engine unit tests against a real Postgres (Testcontainers).
 *
 * <p>Covers the six cases the plan calls out (price-time priority, partial fill
 * cascade, MARKET full fill, MARKET insufficient liquidity, MARKET on empty
 * book, LIMIT that doesn't cross), plus cancel happy/sad paths and
 * idempotency.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MatchingEngineServiceTest {

    @Autowired private MatchingEngineService engine;
    @Autowired private OrderRepository orders;
    @Autowired private TradeRepository trades;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        // Wipe match-state between tests; symbols + users (V2/V3) are preserved.
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
    }

    // ---------- Phase 3.6 cases ----------

    @Test
    void limitThatDoesNotCrossRestsAsOpen() {
        OrderResult res = engine.submit(buy("c1", u1(), "AAPL", "175.00", 100));

        assertThat(res.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(res.filledQty()).isZero();
        assertThat(res.idempotentReplay()).isFalse();
        assertThat(trades.findBySymbol("AAPL")).isEmpty();
    }

    @Test
    void priceTimePriorityFavoursBetterPriceAcrossLevels() {
        // Three asks: 182, 180, 181 (in that submission order).
        engine.submit(sell("a1", u2(), "AAPL", "182.00", 50));
        engine.submit(sell("a2", u3(), "AAPL", "180.00", 50));
        engine.submit(sell("a3", u4(), "AAPL", "181.00", 50));

        // BUY 60 at 182 should sweep a2 (50 @ 180) then a3 (10 @ 181).
        OrderResult res = engine.submit(buy("b1", u1(), "AAPL", "182.00", 60));
        assertThat(res.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(res.filledQty()).isEqualTo(60);

        List<TradeRow> ts = trades.findBySymbol("AAPL");
        assertThat(ts).hasSize(2);
        assertThat(ts.get(0).price()).isEqualByComparingTo("180.00");
        assertThat(ts.get(0).quantity()).isEqualTo(50);
        assertThat(ts.get(1).price()).isEqualByComparingTo("181.00");
        assertThat(ts.get(1).quantity()).isEqualTo(10);

        assertThat(orders.findByClientOrderId(u4(), "a3").orElseThrow().status())
                .isEqualTo(OrderStatus.PARTIAL);
        assertThat(orders.findByClientOrderId(u2(), "a1").orElseThrow().status())
                .isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void timePriorityFavoursEarlierOrderWithinSameLevel() {
        engine.submit(sell("a1", u2(), "AAPL", "180.00", 30));
        engine.submit(sell("a2", u3(), "AAPL", "180.00", 30));

        OrderResult res = engine.submit(buy("b1", u1(), "AAPL", "180.00", 30));
        assertThat(res.status()).isEqualTo(OrderStatus.FILLED);

        assertThat(orders.findByClientOrderId(u2(), "a1").orElseThrow().status())
                .isEqualTo(OrderStatus.FILLED);
        assertThat(orders.findByClientOrderId(u3(), "a2").orElseThrow().status())
                .isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void marketAgainstEmptyBookIsCancelledWithInsufficientLiquidity() {
        OrderResult res = engine.submit(market("m1", u1(), "AAPL", OrderSide.BUY, 100));

        assertThat(res.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(res.filledQty()).isZero();
        assertThat(res.rejectReason()).isEqualTo(MatchingEngineService.INSUFFICIENT_LIQUIDITY);
    }

    @Test
    void marketFullyFillsWhenSufficientLiquidityExists() {
        engine.submit(sell("a1", u2(), "AAPL", "180.00", 100));
        engine.submit(sell("a2", u3(), "AAPL", "182.00", 100));

        OrderResult res = engine.submit(market("m1", u1(), "AAPL", OrderSide.BUY, 150));

        assertThat(res.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(res.filledQty()).isEqualTo(150);
        assertThat(res.rejectReason()).isNull();
    }

    @Test
    void marketWithLeftoverPartiallyFillsThenCancels() {
        engine.submit(sell("a1", u2(), "AAPL", "180.00", 50));

        OrderResult res = engine.submit(market("m1", u1(), "AAPL", OrderSide.BUY, 100));

        // MARKET orders never rest as PARTIAL; the order itself ends CANCELLED with
        // reject_reason='INSUFFICIENT_LIQUIDITY' and filled_qty preserved.
        assertThat(res.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(res.filledQty()).isEqualTo(50);
        assertThat(res.rejectReason()).isEqualTo(MatchingEngineService.INSUFFICIENT_LIQUIDITY);

        assertThat(trades.findBySymbol("AAPL")).hasSize(1);
        assertThat(trades.findBySymbol("AAPL").get(0).quantity()).isEqualTo(50);
    }

    // ---------- Idempotency ----------

    @Test
    void duplicateClientOrderIdReturnsExistingOrderWithoutMatching() {
        OrderResult first = engine.submit(buy("c1", u1(), "AAPL", "175.00", 100));
        OrderResult second = engine.submit(buy("c1", u1(), "AAPL", "175.00", 100));

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.idempotentReplay()).isTrue();
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(orders.findAll()).hasSize(1);
    }

    // ---------- Cancel ----------

    @Test
    void cancelOpenOrderTransitionsToCancelled() {
        OrderResult res = engine.submit(buy("c1", u1(), "AAPL", "175.00", 100));

        CancelResult cancelRes = engine.cancel(new CancelOrderCommand(res.orderId(), u1()));

        assertThat(cancelRes.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelRes.filledQty()).isZero();
        assertThat(orders.findById(res.orderId()).orElseThrow().status())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOfPartialFillPreservesFilledQty() {
        engine.submit(sell("a1", u2(), "AAPL", "180.00", 50));
        OrderResult buyRes = engine.submit(buy("b1", u1(), "AAPL", "180.00", 100));
        assertThat(buyRes.status()).isEqualTo(OrderStatus.PARTIAL);
        assertThat(buyRes.filledQty()).isEqualTo(50);

        CancelResult cancelRes = engine.cancel(new CancelOrderCommand(buyRes.orderId(), u1()));

        assertThat(cancelRes.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelRes.filledQty()).isEqualTo(50);
        OrderRow row = orders.findById(buyRes.orderId()).orElseThrow();
        assertThat(row.filledQty()).isEqualTo(50);
        assertThat(row.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelByNonOwnerThrowsAccessDenied() {
        OrderResult res = engine.submit(buy("c1", u1(), "AAPL", "175.00", 100));

        assertThatThrownBy(() -> engine.cancel(new CancelOrderCommand(res.orderId(), u2())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancelOfFilledOrderThrowsIllegalState() {
        engine.submit(sell("a1", u2(), "AAPL", "180.00", 100));
        OrderResult buyRes = engine.submit(buy("b1", u1(), "AAPL", "180.00", 100));
        assertThat(buyRes.status()).isEqualTo(OrderStatus.FILLED);

        assertThatThrownBy(() -> engine.cancel(new CancelOrderCommand(buyRes.orderId(), u1())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILLED");
    }

    @Test
    void cancelOfMissingOrderThrowsOrderNotFound() {
        assertThatThrownBy(() -> engine.cancel(new CancelOrderCommand(UUID.randomUUID(), u1())))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ---------- helpers ----------

    private static UUID seedUserId(String username) {
        return UUID.nameUUIDFromBytes(("seed-user-" + username).getBytes());
    }

    private static UUID u1() { return seedUserId("u1"); }
    private static UUID u2() { return seedUserId("u2"); }
    private static UUID u3() { return seedUserId("u3"); }
    private static UUID u4() { return seedUserId("u4"); }

    private static SubmitOrderCommand buy(String clientId, UUID userId, String symbol, String price, long qty) {
        return new SubmitOrderCommand(clientId, userId, symbol,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static SubmitOrderCommand sell(String clientId, UUID userId, String symbol, String price, long qty) {
        return new SubmitOrderCommand(clientId, userId, symbol,
                OrderSide.SELL, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    private static SubmitOrderCommand market(String clientId, UUID userId, String symbol, OrderSide side, long qty) {
        return new SubmitOrderCommand(clientId, userId, symbol, side, OrderType.MARKET, null, qty);
    }
}
