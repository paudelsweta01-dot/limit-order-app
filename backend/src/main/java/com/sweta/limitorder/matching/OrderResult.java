package com.sweta.limitorder.matching;

import com.sweta.limitorder.persistence.OrderStatus;

import java.util.UUID;

public record OrderResult(
        UUID orderId,
        OrderStatus status,
        long filledQty,
        String rejectReason,
        boolean idempotentReplay
) {
}
