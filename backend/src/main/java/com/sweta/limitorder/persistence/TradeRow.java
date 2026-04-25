package com.sweta.limitorder.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read snapshot of a row from the {@code trades} table.
 * Trades are write-once; this record never describes a "to be inserted" trade
 * with a null {@code executedAt} — see {@link TradeRepository#insertNew}.
 */
public record TradeRow(
        UUID tradeId,
        String symbol,
        UUID buyOrderId,
        UUID sellOrderId,
        UUID buyUserId,
        UUID sellUserId,
        BigDecimal price,
        long quantity,
        Instant executedAt
) {
}
