package com.sweta.limitorder.simulator.api.dto;

import java.util.UUID;

public record CancelOrderResponse(UUID orderId, OrderStatus status, long filledQty) {}
