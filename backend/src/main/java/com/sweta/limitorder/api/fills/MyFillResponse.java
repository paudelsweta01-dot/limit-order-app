package com.sweta.limitorder.api.fills;

import com.sweta.limitorder.persistence.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for {@code GET /api/fills/mine}. Mirrors the columns the §6.4
 * "My Fills" wireframe needs: TradeId, Symbol, Side, Price, Qty, Time,
 * Counter (counterparty's username — looked up server-side by joining
 * trades to users).
 */
public record MyFillResponse(
        UUID tradeId,
        String symbol,
        OrderSide side,
        BigDecimal price,
        long quantity,
        Instant executedAt,
        String counterparty
) {
}
