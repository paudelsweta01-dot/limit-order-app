package com.sweta.limitorder.api.orders;

import com.sweta.limitorder.persistence.OrderStatus;

import java.util.UUID;

public record CancelOrderResponse(UUID orderId, OrderStatus status, long filledQty) {
}
