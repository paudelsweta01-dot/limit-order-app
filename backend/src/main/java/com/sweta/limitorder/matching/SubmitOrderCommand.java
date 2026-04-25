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
