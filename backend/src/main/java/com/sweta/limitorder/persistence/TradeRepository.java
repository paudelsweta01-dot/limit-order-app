package com.sweta.limitorder.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TradeRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<TradeRow> MAPPER = (rs, n) -> new TradeRow(
            rs.getObject("trade_id", UUID.class),
            rs.getString("symbol"),
            rs.getObject("buy_order_id", UUID.class),
            rs.getObject("sell_order_id", UUID.class),
            rs.getObject("buy_user_id", UUID.class),
            rs.getObject("sell_user_id", UUID.class),
            rs.getBigDecimal("price"),
            rs.getLong("quantity"),
            toInstant(rs, "executed_at"));

    public void insertNew(UUID tradeId,
                          String symbol,
                          UUID buyOrderId,
                          UUID sellOrderId,
                          UUID buyUserId,
                          UUID sellUserId,
                          BigDecimal price,
                          long quantity) {
        jdbc.update("""
                INSERT INTO trades (
                    trade_id, symbol, buy_order_id, sell_order_id,
                    buy_user_id, sell_user_id, price, quantity
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tradeId, symbol, buyOrderId, sellOrderId,
                buyUserId, sellUserId, price, quantity);
    }

    public List<TradeRow> findBySymbol(String symbol) {
        return jdbc.query("""
                SELECT trade_id, symbol, buy_order_id, sell_order_id,
                       buy_user_id, sell_user_id, price, quantity, executed_at
                  FROM trades
                 WHERE symbol = ?
                 ORDER BY executed_at ASC
                """, MAPPER, symbol);
    }

    public List<TradeRow> findAll() {
        return jdbc.query("""
                SELECT trade_id, symbol, buy_order_id, sell_order_id,
                       buy_user_id, sell_user_id, price, quantity, executed_at
                  FROM trades
                 ORDER BY executed_at ASC
                """, MAPPER);
    }

    private static java.time.Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }
}
