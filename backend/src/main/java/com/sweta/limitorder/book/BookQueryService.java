package com.sweta.limitorder.book;

import com.sweta.limitorder.persistence.OrderSide;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-side queries that drive the §6.2 Market Overview and §6.3 Symbol
 * Detail screens.
 *
 * <p>{@link #snapshot} runs at REPEATABLE READ so the four reads it
 * combines — bids, asks, last trade, outbox cursor — observe the same
 * MVCC snapshot. Without that, a write committed mid-method could leave
 * the cursor pointing at an event that's already in the snapshot's
 * underlying state (or vice versa), defeating the snapshot/event race fix
 * the WebSocket layer relies on (architecture §4.8). At Postgres' default
 * READ COMMITTED, every statement gets its own MVCC view; REPEATABLE READ
 * pins them.
 */
@Service
@RequiredArgsConstructor
public class BookQueryService {

    private static final int TOP_LEVELS = 5;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BookSnapshot snapshot(String symbol) {
        List<BookLevel> bids = topNLevels(symbol, OrderSide.BUY,  TOP_LEVELS);
        List<BookLevel> asks = topNLevels(symbol, OrderSide.SELL, TOP_LEVELS);
        BigDecimal last = lastTradePrice(symbol);
        long cursor = currentOutboxCursor();
        return new BookSnapshot(symbol, bids, asks, last, cursor);
    }

    @Transactional(readOnly = true)
    public BookTotals totals(String symbol) {
        long demand = sumOpenQuantity(symbol, OrderSide.BUY);
        long supply = sumOpenQuantity(symbol, OrderSide.SELL);
        return new BookTotals(demand, supply);
    }

    // ---------- internals ----------

    private List<BookLevel> topNLevels(String symbol, OrderSide side, int n) {
        // Bids: best (highest) first. Asks: best (lowest) first.
        String orderDir = (side == OrderSide.BUY) ? "DESC" : "ASC";
        return jdbc.query(
                "SELECT price, " +
                        "       SUM(quantity - filled_qty) AS qty, " +
                        "       COUNT(DISTINCT user_id) AS user_count " +
                        "  FROM orders " +
                        " WHERE symbol = ? AND side = ?::order_side AND status IN ('OPEN','PARTIAL') " +
                        " GROUP BY price " +
                        " ORDER BY price " + orderDir + " " +
                        " LIMIT ?",
                (rs, rn) -> new BookLevel(
                        rs.getBigDecimal("price"),
                        rs.getLong("qty"),
                        rs.getInt("user_count")),
                symbol, side.name(), n);
    }

    private BigDecimal lastTradePrice(String symbol) {
        try {
            return jdbc.queryForObject(
                    "SELECT price FROM trades WHERE symbol = ? ORDER BY executed_at DESC LIMIT 1",
                    BigDecimal.class, symbol);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private long currentOutboxCursor() {
        Long cursor = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM market_event_outbox",
                Long.class);
        return cursor != null ? cursor : 0L;
    }

    private long sumOpenQuantity(String symbol, OrderSide side) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity - filled_qty), 0) " +
                        "  FROM orders " +
                        " WHERE symbol = ? AND side = ?::order_side AND status IN ('OPEN','PARTIAL')",
                Long.class, symbol, side.name());
        return total != null ? total : 0L;
    }
}
