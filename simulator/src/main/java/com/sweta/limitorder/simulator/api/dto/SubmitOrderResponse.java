package com.sweta.limitorder.simulator.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitOrderResponse(
        UUID orderId,
        OrderStatus status,
        long filledQty,
        String rejectReason,
        boolean idempotentReplay
) {}
