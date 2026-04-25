package com.sweta.limitorder.matching;

import com.sweta.limitorder.persistence.OrderStatus;

import java.util.UUID;

public record CancelResult(UUID orderId, OrderStatus status, long filledQty) {
}
