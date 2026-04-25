package com.sweta.limitorder.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate-backed repository for the {@code orders} table.
 *
 * <p>Custom SQL (no JPA) is deliberate — the matching path needs precise control
 * over the ORDER BY, the partial-index hit, {@code FOR UPDATE} on the resting
 * row, and Postgres ENUM casts. JPA would obscure all of those.
 */
@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final JdbcTemplate jdbc;

    private static final String SELECT_COLUMNS = """
            order_id, client_order_id, user_id, symbol, side, type, price,
            quantity, filled_qty, status, reject_reason, created_at, updated_at
            """;

    private static final RowMapper<OrderRow> MAPPER = OrderRepository::mapRow;

    // ---------- Reads ----------

    public Optional<OrderRow> findById(UUID orderId) {
        return findOne(
                "SELECT " + SELECT_COLUMNS + " FROM orders WHERE order_id = ?",
                orderId);
    }

    public Optional<OrderRow> findByClientOrderId(UUID userId, String clientOrderId) {
        return findOne(
                "SELECT " + SELECT_COLUMNS + " FROM orders " +
                        "WHERE user_id = ? AND client_order_id = ?",
                userId, clientOrderId);
    }

    /**
     * Returns the best opposite-side resting order for the incoming order, if any.
     *
     * <p>The query intentionally hits the partial index
     * {@code orders_book_idx (symbol, side, price, created_at) WHERE status IN ('OPEN','PARTIAL')},
     * orders by (price, time) per architecture §2.2, and locks the row
     * {@code FOR UPDATE} so the surrounding match transaction has exclusive
     * access until commit.
     *
     * @param symbol         the symbol being matched
     * @param incomingSide   the incoming order's side; the scan looks at the opposite side
     * @param incomingType   {@link OrderType#MARKET} drops the price predicate entirely
     * @param incomingLimit  required for {@link OrderType#LIMIT}; ignored for MARKET
     */
    public Optional<OrderRow> selectBestOpposite(String symbol,
                                                 OrderSide incomingSide,
                                                 OrderType incomingType,
                                                 BigDecimal incomingLimit) {
        OrderSide oppositeSide = incomingSide.opposite();

        String pricePredicate;
        String orderBy;
        if (incomingType == OrderType.MARKET) {
            pricePredicate = "";
            orderBy = (incomingSide == OrderSide.BUY)
                    ? "ORDER BY price ASC, created_at ASC"
                    : "ORDER BY price DESC, created_at ASC";
        } else if (incomingSide == OrderSide.BUY) {
            pricePredicate = "AND price <= ? ";
            orderBy = "ORDER BY price ASC, created_at ASC";
        } else {
            pricePredicate = "AND price >= ? ";
            orderBy = "ORDER BY price DESC, created_at ASC";
        }

        String sql = "SELECT " + SELECT_COLUMNS + " FROM orders " +
                "WHERE symbol = ? " +
                "  AND side = ?::order_side " +
                "  AND status IN ('OPEN', 'PARTIAL') " +
                "  " + pricePredicate +
                orderBy + " LIMIT 1 FOR UPDATE";

        Object[] args = (incomingType == OrderType.MARKET)
                ? new Object[]{symbol, oppositeSide.name()}
                : new Object[]{symbol, oppositeSide.name(), incomingLimit};

        return findOne(sql, args);
    }

    // ---------- Writes ----------

    /**
     * Inserts an OPEN order. Caller supplies the {@code orderId}; created_at /
     * updated_at default to {@code now()} server-side, which is what we want for
     * time priority (see architecture §9.1).
     */
    public void insertNew(OrderRow order) {
        jdbc.update("""
                INSERT INTO orders (
                    order_id, client_order_id, user_id, symbol, side, type, price,
                    quantity, filled_qty, status
                ) VALUES (?, ?, ?, ?, ?::order_side, ?::order_type, ?, ?, ?, ?::order_status)
                """,
                order.orderId(),
                order.clientOrderId(),
                order.userId(),
                order.symbol(),
                order.side().name(),
                order.type().name(),
                order.price(),
                order.quantity(),
                order.filledQty(),
                order.status().name());
    }

    /**
     * Adds {@code addQty} to {@code filled_qty} and transitions to {@code newStatus}.
     * Returns the number of rows affected (always 1 in the happy path).
     *
     * <p>The {@code orders.filled_qty <= quantity} CHECK constraint will throw if
     * a buggy caller tries to over-fill — the DB is the last line of defence
     * against the §3 NFR "no trade for more than the resting quantity".
     */
    public int applyFill(UUID orderId, long addQty, OrderStatus newStatus) {
        return jdbc.update("""
                UPDATE orders
                   SET filled_qty = filled_qty + ?,
                       status     = ?::order_status,
                       updated_at = now()
                 WHERE order_id   = ?
                """, addQty, newStatus.name(), orderId);
    }

    public int markRejected(UUID orderId, String reason) {
        return jdbc.update("""
                UPDATE orders
                   SET status        = 'CANCELLED',
                       reject_reason = ?,
                       updated_at    = now()
                 WHERE order_id      = ?
                """, reason, orderId);
    }

    public int markCancelled(UUID orderId) {
        return jdbc.update("""
                UPDATE orders
                   SET status     = 'CANCELLED',
                       updated_at = now()
                 WHERE order_id   = ?
                """, orderId);
    }

    // ---------- internals ----------

    private Optional<OrderRow> findOne(String sql, Object... args) {
        try {
            return Optional.of(jdbc.queryForObject(sql, MAPPER, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static OrderRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("order_id", UUID.class),
                rs.getString("client_order_id"),
                rs.getObject("user_id", UUID.class),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("type")),
                rs.getBigDecimal("price"),
                rs.getLong("quantity"),
                rs.getLong("filled_qty"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private static java.time.Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    /**
     * "My orders" page (architecture §6.4) — newest first, hits
     * {@code orders_user_idx (user_id, created_at DESC)}.
     */
    public List<OrderRow> findByUser(UUID userId) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM orders " +
                        "WHERE user_id = ? ORDER BY created_at DESC",
                MAPPER, userId);
    }

    /** For tests / diagnostics: paginate over all orders with a given status. */
    public List<OrderRow> findAll() {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM orders ORDER BY created_at", MAPPER);
    }
}
