package com.sweta.limitorder.api.orders;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweta.limitorder.persistence.OrderStatus;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitOrderResponse(
        UUID orderId,
        OrderStatus status,
        long filledQty,
        String rejectReason,
        boolean idempotentReplay
) {
}
