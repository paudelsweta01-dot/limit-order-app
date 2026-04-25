package com.sweta.limitorder.book;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Top-5-level book + last trade price + outbox cursor for a single symbol.
 *
 * <p>The {@code cursor} is the outbox row id at the moment the snapshot was
 * read. The WebSocket layer (Phase 8) tags every event it forwards with its
 * outbox id; the client drops events whose id is {@code <= cursor}. This
 * eliminates the race between "snapshot read" and "first delta arrives"
 * (architecture §4.8). Read consistency between the level reads and the
 * cursor read is provided by the REPEATABLE READ transaction in
 * {@link BookQueryService}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookSnapshot(
        String symbol,
        List<BookLevel> bids,
        List<BookLevel> asks,
        BigDecimal last,
        long cursor
) {
}
