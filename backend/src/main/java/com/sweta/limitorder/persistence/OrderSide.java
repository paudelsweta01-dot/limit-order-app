package com.sweta.limitorder.persistence;

public enum OrderSide {
    BUY,
    SELL;

    public OrderSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
