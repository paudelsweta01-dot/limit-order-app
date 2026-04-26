package com.sweta.limitorder.simulator.api.dto;

import java.math.BigDecimal;

/**
 * Mirrors {@code com.sweta.limitorder.api.orders.SubmitOrderRequest}.
 *
 * <p>{@code price} is BigDecimal so trailing zeros are preserved
 * end-to-end (architecture §9.2). MARKET orders pass null.
 */
public record SubmitOrderRequest(
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity
) {}
