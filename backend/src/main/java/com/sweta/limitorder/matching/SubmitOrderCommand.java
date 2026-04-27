package com.sweta.limitorder.matching;

import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Validated input to {@link MatchingEngineService#submit}.
 *
 * <p>Validation lives upstream (controller / service caller). The engine
 * trusts these fields and only enforces business invariants — never input shape.
 */
public record SubmitOrderCommand(
        String clientOrderId,
        UUID userId,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity
) {

    /**
     * Plan §10.4 — sane upper bound on quantity. 10^9 is well above the
     * spec's 5000-orders/min × 5-symbol scale and below the engine's
     * BIGINT * NUMERIC(12,4) overflow threshold (≈ 9.2 × 10^14 / 10^9
     * = 9.2 × 10^5 worth of price headroom). Anything bigger is almost
     * certainly a bug in the caller.
     */
    public static final long MAX_QUANTITY = 1_000_000_000L;

    public SubmitOrderCommand {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        if (userId == null) throw new IllegalArgumentException("userId is required");
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (side == null) throw new IllegalArgumentException("side is required");
        if (type == null) throw new IllegalArgumentException("type is required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity exceeds upper bound (" + MAX_QUANTITY + ")");
        }
        if (type == OrderType.LIMIT && price == null) {
            throw new IllegalArgumentException("LIMIT orders require a price");
        }
        if (type == OrderType.MARKET && price != null) {
            throw new IllegalArgumentException("MARKET orders must not carry a price");
        }
        if (type == OrderType.LIMIT && price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
    }
}
