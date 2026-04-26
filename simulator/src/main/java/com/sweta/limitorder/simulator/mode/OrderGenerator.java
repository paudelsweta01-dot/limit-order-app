package com.sweta.limitorder.simulator.mode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.api.dto.OrderType;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;

/**
 * Plan §5.3 — generates a single random order from a seedable RNG so
 * load runs reproduce exactly with {@code --seed}.
 *
 * <ul>
 *   <li>{@code symbol ~ U{symbols}}</li>
 *   <li>{@code side ~ Bernoulli(0.5)} — 50% BUY / 50% SELL</li>
 *   <li>{@code type ~ Bernoulli(0.7 LIMIT, 0.3 MARKET)}</li>
 *   <li>{@code price = refPrice * (1 + N(0, 0.01))} rounded to 4dp;
 *       MARKET orders carry null per backend's cross-field rule</li>
 *   <li>{@code qty ~ U[10, 500]}</li>
 * </ul>
 */
public final class OrderGenerator {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final double PRICE_NOISE_SIGMA = 0.01;
    private static final double LIMIT_PROBABILITY = 0.70;
    private static final int QTY_LO = 10;
    private static final int QTY_HI = 500; // inclusive upper bound

    private final List<String> symbols;
    private final Map<String, BigDecimal> refPrices;
    private final Random rng;

    public OrderGenerator(List<String> symbols, Map<String, BigDecimal> refPrices, long seed) {
        this(symbols, refPrices, new Random(seed));
    }

    public OrderGenerator(List<String> symbols, Map<String, BigDecimal> refPrices, Random rng) {
        if (symbols.isEmpty()) throw new IllegalArgumentException("symbols must be non-empty");
        this.symbols = List.copyOf(symbols);
        this.refPrices = Map.copyOf(refPrices);
        this.rng = rng;
    }

    /** Mints one order; clientOrderId is fresh per call. */
    public SubmitOrderRequest next() {
        String symbol = symbols.get(rng.nextInt(symbols.size()));
        OrderSide side = rng.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
        OrderType type = rng.nextDouble() < LIMIT_PROBABILITY ? OrderType.LIMIT : OrderType.MARKET;

        BigDecimal price = null;
        if (type == OrderType.LIMIT) {
            BigDecimal ref = refPrices.getOrDefault(symbol, BigDecimal.valueOf(100));
            double mult = 1.0 + rng.nextGaussian() * PRICE_NOISE_SIGMA;
            // Clamp to ≥ 0.0001 so we never submit a zero/negative price (the
            // tail of the gaussian can dip below zero around 100σ but bounded
            // is more correct here than letting the backend reject).
            BigDecimal raw = ref.multiply(BigDecimal.valueOf(Math.max(0.0001, mult)));
            price = raw.setScale(4, RoundingMode.HALF_UP);
        }

        long qty = QTY_LO + rng.nextInt(QTY_HI - QTY_LO + 1);

        return new SubmitOrderRequest(
                UUID.randomUUID().toString(), // unique enough for the load run; not seedable
                symbol, side, type, price, qty);
    }

    /**
     * Mints one order with a clientOrderId derived from the seed-driven
     * sequence — used by tests that assert {@code --seed=N} produces
     * identical client-order-ids across runs. Production load uses
     * {@link #next()} which always uses {@link UUID#randomUUID()}.
     */
    public SubmitOrderRequest nextDeterministic(int sequence) {
        SubmitOrderRequest base = next();
        return new SubmitOrderRequest(
                "load-%010d-%016x".formatted(sequence, rng.nextLong()),
                base.symbol(), base.side(), base.type(), base.price(), base.quantity());
    }
}
