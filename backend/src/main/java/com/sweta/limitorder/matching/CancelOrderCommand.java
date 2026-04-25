package com.sweta.limitorder.matching;

import java.util.UUID;

public record CancelOrderCommand(UUID orderId, UUID userId) {

    public CancelOrderCommand {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (userId == null)  throw new IllegalArgumentException("userId is required");
    }
}
