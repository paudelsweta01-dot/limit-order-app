package com.sweta.limitorder.api.orders;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweta.limitorder.persistence.OrderRow;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderStatus;
import com.sweta.limitorder.persistence.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for {@code GET /api/orders/mine}. Mirrors the columns the
 * §6.4 wireframe needs (OrderId, Symbol, Side, Type, Price, Qty, Filled,
 * Status) plus the timestamps the frontend uses for sorting and display.
 */
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
) {

    public static MyOrderResponse from(OrderRow row) {
        return new MyOrderResponse(
                row.orderId(),
                row.clientOrderId(),
                row.symbol(),
                row.side(),
                row.type(),
                row.price(),
                row.quantity(),
                row.filledQty(),
                row.status(),
                row.rejectReason(),
                row.createdAt(),
                row.updatedAt());
    }
}
