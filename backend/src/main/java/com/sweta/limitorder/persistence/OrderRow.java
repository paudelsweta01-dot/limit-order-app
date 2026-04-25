package com.sweta.limitorder.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read snapshot of a row from the {@code orders} table.
 *
 * <p>Distinct from a JPA entity: the matching engine uses {@link OrderRepository}
 * over JdbcTemplate exclusively (per architecture §4.2 and Phase 3 plan), so
 * orders never flow through Hibernate. Records keep the data flat and final.
 */
public record OrderRow(
        UUID orderId,
        String clientOrderId,
        UUID userId,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity,
        long filledQty,
        OrderStatus status,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt
) {

    public long remainingQty() {
        return quantity - filledQty;
    }
}
