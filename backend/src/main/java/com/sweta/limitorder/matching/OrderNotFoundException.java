package com.sweta.limitorder.matching;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("order not found: " + orderId);
    }
}
