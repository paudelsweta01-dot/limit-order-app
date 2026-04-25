package com.sweta.limitorder.persistence;

public enum OrderStatus {
    OPEN,
    PARTIAL,
    FILLED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED;
    }

    public boolean isCancellable() {
        return this == OPEN || this == PARTIAL;
    }
}
