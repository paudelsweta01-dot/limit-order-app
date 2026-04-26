package com.sweta.limitorder.simulator.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyOrderResponse(
        UUID orderId,
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity,
        long filledQty,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt
) {}
