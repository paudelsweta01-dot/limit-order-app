package com.sweta.limitorder.matching;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.persistence.OrderRepository;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderType;
import com.sweta.limitorder.persistence.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.7 — concurrency soak.
 *
 * <p>Many threads submit random BUY/SELL/MARKET orders across the five
 * seeded symbols against the matching engine in parallel. At the end we
 * assert the §4.3 consistency invariants:
 *
 * <ol>
 *   <li>Σ filled_qty(BUY) == Σ filled_qty(SELL) for every symbol.</li>
 *   <li>No order has filled_qty &gt; quantity (DB-enforced too).</li>
 *   <li>Every trade references valid opposite-side orders with matching
 *       user ids.</li>
 * </ol>
 *
 * <p>Scale is deliberately tuned (50 threads × 50 orders = 2,500 submissions)
 * to fit the 60s plan budget. The per-symbol advisory lock serialises matches
 * on each of 5 symbols; pushing this past ~5k–10k submissions runs into the
 * throughput ceiling architecture §4.3 documents — the *invariants* we're
 * testing hold the same at any scale.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ConcurrencySoakTest {

    private static final int THREAD_COUNT = 50;
    private static final int ORDERS_PER_THREAD = 50;
    private static final long RNG_SEED_BASE = 42L;

    private static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "GOOGL", "TSLA", "AMZN");
    private static final Map<String, BigDecimal> REF_PRICE = Map.of(
            "AAPL",  new BigDecimal("180.00"),
            "MSFT",  new BigDecimal("420.00"),
            "GOOGL", new BigDecimal("155.00"),
            "TSLA",  new BigDecimal("240.00"),
            "AMZN",  new BigDecimal("190.00")
    );
    private static final List<String> USERNAMES = List.of("u1", "u2", "u3", "u4");

    @Autowired private MatchingEngineService engine;
    @Autowired private OrderRepository orders;
    @Autowired private TradeRepository trades;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orders, trades, market_event_outbox RESTART IDENTITY CASCADE");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parallelSubmissionsPreserveAllConsistencyInvariants() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>(THREAD_COUNT);
        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadIdx = t;
            tasks.add(() -> {
                Random rng = new Random(RNG_SEED_BASE + threadIdx);
                for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                    String clientId = "t" + threadIdx + "-o" + i;
                    UUID userId = seedUserId(USERNAMES.get(rng.nextInt(USERNAMES.size())));
                    String symbol = SYMBOLS.get(rng.nextInt(SYMBOLS.size()));
                    OrderSide side = rng.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
                    OrderType type = rng.nextDouble() < 0.7 ? OrderType.LIMIT : OrderType.MARKET;
                    BigDecimal price = (type == OrderType.LIMIT) ? randomPrice(rng, symbol) : null;
                    long quantity = 10 + rng.nextInt(491);
                    engine.submit(new SubmitOrderCommand(
                            clientId, userId, symbol, side, type, price, quantity));
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            List<Future<Void>> results = pool.invokeAll(tasks);
            // Re-throw any underlying exception so the test fails on the actual cause.
            for (Future<Void> r : results) {
                r.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // ---------- Invariant (a): per-symbol BUY/SELL fill-qty parity ----------
        for (String symbol : SYMBOLS) {
            Long buyTotal = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(filled_qty), 0) FROM orders WHERE symbol = ? AND side = 'BUY'",
                    Long.class, symbol);
            Long sellTotal = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(filled_qty), 0) FROM orders WHERE symbol = ? AND side = 'SELL'",
                    Long.class, symbol);
            assertThat(buyTotal)
                    .as("Σ filled_qty(BUY) must equal Σ filled_qty(SELL) for %s", symbol)
                    .isEqualTo(sellTotal);
        }

        // ---------- Invariant (b): no filled_qty > quantity ----------
        Long overFilled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE filled_qty > quantity", Long.class);
        assertThat(overFilled)
                .as("no order may have filled_qty > quantity")
                .isZero();

        // ---------- Invariant (c): every trade references valid opposite-side orders ----------
        Long brokenTrades = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trades t
                  LEFT JOIN orders bo ON bo.order_id = t.buy_order_id
                  LEFT JOIN orders so ON so.order_id = t.sell_order_id
                 WHERE bo.order_id  IS NULL
                    OR so.order_id  IS NULL
                    OR bo.user_id  != t.buy_user_id
                    OR so.user_id  != t.sell_user_id
                    OR bo.side     != 'BUY'
                    OR so.side     != 'SELL'
                    OR t.symbol    != bo.symbol
                    OR t.symbol    != so.symbol
                """, Long.class);
        assertThat(brokenTrades)
                .as("every trade must reference valid opposite-side orders with matching users")
                .isZero();

        // ---------- Visibility: print summary so a human can sanity-check it ----------
        long totalOrders = orders.findAll().size();
        long totalTrades = trades.findAll().size();
        System.out.printf(
                "Soak summary: %d orders submitted, %d trades produced across %d symbols%n",
                totalOrders, totalTrades, SYMBOLS.size());
    }

    private static BigDecimal randomPrice(Random rng, String symbol) {
        // ±1% Gaussian noise around the seeded reference price; same shape as the
        // simulator's load profile (§5.5).
        BigDecimal ref = REF_PRICE.get(symbol);
        double noise = rng.nextGaussian() * 0.01;
        BigDecimal price = ref.multiply(BigDecimal.valueOf(1.0 + noise))
                .setScale(4, RoundingMode.HALF_EVEN);
        return price.signum() > 0 ? price : ref;
    }

    private static UUID seedUserId(String u) {
        return UUID.nameUUIDFromBytes(("seed-user-" + u).getBytes());
    }
}
