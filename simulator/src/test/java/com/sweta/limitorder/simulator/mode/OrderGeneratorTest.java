package com.sweta.limitorder.simulator.mode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.api.dto.OrderType;

/**
 * Plan §5.3 — order generator's distribution shape and seed
 * reproducibility.
 */
class OrderGeneratorTest {

    private static final List<String> SYMBOLS = List.of("AAPL", "AMZN", "GOOGL", "MSFT", "TSLA");
    private static final Map<String, BigDecimal> REF_PRICES = Map.of(
            "AAPL",  new BigDecimal("180.00"),
            "AMZN",  new BigDecimal("190.00"),
            "GOOGL", new BigDecimal("155.00"),
            "MSFT",  new BigDecimal("420.00"),
            "TSLA",  new BigDecimal("240.00"));

    @Test
    void sameSeedProducesIdenticalOrderSequence() {
        var a = new OrderGenerator(SYMBOLS, REF_PRICES, 42L);
        var b = new OrderGenerator(SYMBOLS, REF_PRICES, 42L);
        for (int i = 0; i < 100; i++) {
            var x = a.next();
            var y = b.next();
            assertThat(x.symbol()).isEqualTo(y.symbol());
            assertThat(x.side()).isEqualTo(y.side());
            assertThat(x.type()).isEqualTo(y.type());
            if (x.price() != null) {
                assertThat(x.price()).isEqualByComparingTo(y.price());
            } else {
                assertThat(y.price()).isNull();
            }
            assertThat(x.quantity()).isEqualTo(y.quantity());
        }
    }

    @Test
    void sideRatioWithinTolerance() {
        var gen = new OrderGenerator(SYMBOLS, REF_PRICES, 1L);
        int buys = 0, sells = 0;
        for (int i = 0; i < 10_000; i++) {
            if (gen.next().side() == OrderSide.BUY) buys++;
            else sells++;
        }
        double buyRatio = (double) buys / 10_000;
        assertThat(buyRatio).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void typeRatio70_30WithinTolerance() {
        var gen = new OrderGenerator(SYMBOLS, REF_PRICES, 1L);
        int limit = 0, market = 0;
        for (int i = 0; i < 10_000; i++) {
            if (gen.next().type() == OrderType.LIMIT) limit++;
            else market++;
        }
        double limitRatio = (double) limit / 10_000;
        assertThat(limitRatio).isCloseTo(0.70, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void quantityWithinPlanRange_10to500() {
        var gen = new OrderGenerator(SYMBOLS, REF_PRICES, 7L);
        for (int i = 0; i < 10_000; i++) {
            long q = gen.next().quantity();
            assertThat(q).isBetween(10L, 500L);
        }
    }

    @Test
    void marketOrdersHaveNullPrice_limitOrdersHavePrice() {
        var gen = new OrderGenerator(SYMBOLS, REF_PRICES, 13L);
        for (int i = 0; i < 1000; i++) {
            var req = gen.next();
            if (req.type() == OrderType.MARKET) {
                assertThat(req.price()).as("MARKET orders carry null price").isNull();
            } else {
                assertThat(req.price()).as("LIMIT orders carry a price").isNotNull();
                assertThat(req.price().scale()).as("price rounded to 4 dp per plan §5.3").isEqualTo(4);
            }
        }
    }

    @Test
    void priceCentredOnRefPrice_within3SigmaForGaussianTail() {
        // Force LIMIT orders only by checking price field; check distribution
        // for AAPL specifically (refPrice=180, σ=1% → ~1.80).
        var gen = new OrderGenerator(List.of("AAPL"), Map.of("AAPL", new BigDecimal("180.00")), 99L);
        for (int i = 0; i < 1000; i++) {
            var req = gen.next();
            if (req.type() != OrderType.LIMIT) continue;
            // 5σ envelope: ref ± 5 * 0.01 * ref = ±9.0. Generous to cover
            // the long tail of the gaussian without flaking.
            assertThat(req.price().doubleValue()).isBetween(171.0, 189.0);
        }
    }
}
