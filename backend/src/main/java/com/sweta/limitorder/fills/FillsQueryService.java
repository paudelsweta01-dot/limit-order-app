package com.sweta.limitorder.fills;

import com.sweta.limitorder.api.fills.MyFillResponse;
import com.sweta.limitorder.persistence.OrderSide;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-side queries that drive the §6.4 "My Fills" tab.
 *
 * <p>For a given user, walks the {@code trades} table for any trade where
 * they were either the buyer or the seller. The {@code side} and
 * {@code counterparty} fields are computed server-side per
 * architecture §6.5: side = caller's perspective, counterparty = the OTHER
 * user's username (looked up via a join to {@code users}, so the wire shape
 * never leaks UUIDs that the UI doesn't show — see §6.4 wireframe column
 * "Counter | u2").
 */
@Service
@RequiredArgsConstructor
public class FillsQueryService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<MyFillResponse> findByUser(UUID userId) {
        return jdbc.query("""
                SELECT t.trade_id, t.symbol, t.price, t.quantity, t.executed_at,
                       CASE WHEN t.buy_user_id = ? THEN 'BUY' ELSE 'SELL' END  AS side,
                       CASE WHEN t.buy_user_id = ? THEN su.username ELSE bu.username END AS counterparty
                  FROM trades t
                  JOIN users bu ON bu.user_id = t.buy_user_id
                  JOIN users su ON su.user_id = t.sell_user_id
                 WHERE t.buy_user_id = ? OR t.sell_user_id = ?
                 ORDER BY t.executed_at DESC
                """,
                (rs, rn) -> new MyFillResponse(
                        rs.getObject("trade_id", UUID.class),
                        rs.getString("symbol"),
                        OrderSide.valueOf(rs.getString("side")),
                        rs.getBigDecimal("price"),
                        rs.getLong("quantity"),
                        rs.getObject("executed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("counterparty")),
                userId, userId, userId, userId);
    }
}
