package com.sweta.limitorder.orders;

import com.sweta.limitorder.api.orders.MyOrderResponse;
import com.sweta.limitorder.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side snapshot for the {@code /ws/orders/mine} channel.
 *
 * <p>Like {@link com.sweta.limitorder.book.BookQueryService#snapshot}, the
 * order list and the outbox cursor are read inside a single REPEATABLE READ
 * transaction so the cursor reflects exactly the state captured by the
 * snapshot. Without that, a concurrent commit between the two reads could
 * leave the cursor pointing at a row whose effects are already in the
 * snapshot — defeating the snapshot/event race fix in architecture §4.8.
 */
@Service
@RequiredArgsConstructor
public class MyOrdersSnapshotService {

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    public record Snapshot(List<MyOrderResponse> orders, long cursor) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(UUID userId) {
        List<MyOrderResponse> rows = orders.findByUser(userId).stream()
                .map(MyOrderResponse::from)
                .toList();
        Long cursor = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM market_event_outbox", Long.class);
        return new Snapshot(rows, cursor != null ? cursor : 0L);
    }
}
