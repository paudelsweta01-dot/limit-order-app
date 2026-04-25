package com.sweta.limitorder.matching;

import com.sweta.limitorder.observability.EventLog;
import com.sweta.limitorder.outbox.OutboxWriter;
import com.sweta.limitorder.persistence.OrderRepository;
import com.sweta.limitorder.persistence.OrderRow;
import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderStatus;
import com.sweta.limitorder.persistence.OrderType;
import com.sweta.limitorder.persistence.TradeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The project's load-bearing wall.
 *
 * <p>{@link #submit} and {@link #cancel} are the only two state-changing
 * operations on a symbol. Both run inside a single {@code @Transactional}
 * boundary that begins with a per-symbol Postgres advisory lock — the entire
 * algorithm in architecture §4.4 commits atomically or rolls back atomically.
 *
 * <p>Why synchronous + advisory-lock-per-symbol (vs. queue + workers, vs.
 * leader election, vs. {@code SELECT … FOR UPDATE} on a marker row) — see
 * architecture §4.3. The short version: it's the simplest design that
 * preserves the §3 NFR "correctness under concurrency" with a defensible
 * deep-dive story.
 *
 * <p>Observability (Phase 9): every state-changing path emits a structured
 * log event ({@link EventLog}) so {@code grep '"event":"ORDER_FILLED"'} on a
 * captured run finds exactly the right lines, and four Micrometer meters
 * track throughput / rejections / match latency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineService {

    private final AdvisoryLockSupport locks;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public static final String INSUFFICIENT_LIQUIDITY = "INSUFFICIENT_LIQUIDITY";

    /**
     * Submit an order. The full match-and-persist runs inside this transaction
     * with the symbol's advisory lock held — no other submit/cancel for the
     * same symbol on any backend instance can interleave.
     *
     * <p>Idempotent on {@code (userId, clientOrderId)}: a retried POST with
     * the same client_order_id returns the existing order without re-running
     * the match.
     */
    @Transactional
    public OrderResult submit(SubmitOrderCommand cmd) {
        Timer.Sample matchTimer = Timer.start(meters);
        try {
            meters.counter("orders_received_total",
                    "type",   cmd.type().name(),
                    "symbol", cmd.symbol()).increment();

            locks.lockForSymbol(cmd.symbol());

            Optional<OrderRow> existing = orders.findByClientOrderId(cmd.userId(), cmd.clientOrderId());
            if (existing.isPresent()) {
                OrderRow e = existing.get();
                log.info(EventLog.event(EventLog.ORDER_ACCEPTED,
                                "orderId",       e.orderId(),
                                "clientOrderId", e.clientOrderId(),
                                "symbol",        e.symbol(),
                                "status",        e.status().name(),
                                "idempotent",    true),
                        "idempotent replay returns existing order");
                return new OrderResult(e.orderId(), e.status(), e.filledQty(), e.rejectReason(), true);
            }

            UUID orderId = UUID.randomUUID();
            BigDecimal limitPrice = cmd.type() == OrderType.LIMIT ? cmd.price() : null;

            orders.insertNew(new OrderRow(
                    orderId, cmd.clientOrderId(), cmd.userId(), cmd.symbol(),
                    cmd.side(), cmd.type(), limitPrice,
                    cmd.quantity(), 0L, OrderStatus.OPEN,
                    null, null, null));

            log.info(EventLog.event(EventLog.ORDER_ACCEPTED,
                            "orderId",       orderId,
                            "clientOrderId", cmd.clientOrderId(),
                            "userId",        cmd.userId(),
                            "symbol",        cmd.symbol(),
                            "side",          cmd.side().name(),
                            "type",          cmd.type().name(),
                            "price",         limitPrice == null ? null : limitPrice.toPlainString(),
                            "qty",           cmd.quantity()),
                    "order accepted");

            long incomingFilled = 0L;
            long incomingRemaining = cmd.quantity();

            while (incomingRemaining > 0) {
                Optional<OrderRow> bestOpt = orders.selectBestOpposite(
                        cmd.symbol(), cmd.side(), cmd.type(), limitPrice);
                if (bestOpt.isEmpty()) {
                    break;
                }

                OrderRow resting = bestOpt.get();
                long tradeQty = Math.min(incomingRemaining, resting.remainingQty());
                BigDecimal tradePrice = resting.price(); // §2.2: resting order's price wins

                long newIncomingFilled = incomingFilled + tradeQty;
                OrderStatus newIncomingStatus = newIncomingFilled == cmd.quantity()
                        ? OrderStatus.FILLED : OrderStatus.PARTIAL;
                orders.applyFill(orderId, tradeQty, newIncomingStatus);

                long newRestingFilled = resting.filledQty() + tradeQty;
                OrderStatus newRestingStatus = newRestingFilled == resting.quantity()
                        ? OrderStatus.FILLED : OrderStatus.PARTIAL;
                orders.applyFill(resting.orderId(), tradeQty, newRestingStatus);

                UUID tradeId = UUID.randomUUID();
                UUID buyOrderId, sellOrderId, buyUserId, sellUserId;
                if (cmd.side() == OrderSide.BUY) {
                    buyOrderId  = orderId;       sellOrderId = resting.orderId();
                    buyUserId   = cmd.userId();  sellUserId  = resting.userId();
                } else {
                    buyOrderId  = resting.orderId(); sellOrderId = orderId;
                    buyUserId   = resting.userId();  sellUserId  = cmd.userId();
                }

                trades.insertNew(tradeId, cmd.symbol(),
                        buyOrderId, sellOrderId, buyUserId, sellUserId,
                        tradePrice, tradeQty);

                meters.counter("trades_executed_total", "symbol", cmd.symbol()).increment();

                // Outbox events — minimal payloads here; Phase 7 (fan-out) will
                // upgrade to the wire schemas the WS layer needs.
                outbox.emit("trades:" + cmd.symbol(), tradeJson(tradeId, tradePrice, tradeQty));
                outbox.emit("book:"   + cmd.symbol(), bookJson(cmd.symbol()));
                outbox.emit("orders:" + cmd.userId(),     orderJson(orderId, newIncomingStatus, newIncomingFilled));
                outbox.emit("orders:" + resting.userId(), orderJson(resting.orderId(), newRestingStatus, newRestingFilled));

                log.info(EventLog.event(EventLog.TRADE_EXECUTED,
                                "tradeId",     tradeId,
                                "symbol",      cmd.symbol(),
                                "price",       tradePrice.toPlainString(),
                                "qty",         tradeQty,
                                "buyOrderId",  buyOrderId,
                                "sellOrderId", sellOrderId,
                                "buyUserId",   buyUserId,
                                "sellUserId",  sellUserId),
                        "trade executed");

                if (newIncomingStatus == OrderStatus.FILLED) {
                    log.info(EventLog.event(EventLog.ORDER_FILLED,
                                    "orderId", orderId,
                                    "userId",  cmd.userId(),
                                    "symbol",  cmd.symbol(),
                                    "qty",     cmd.quantity()),
                            "incoming order filled");
                }
                if (newRestingStatus == OrderStatus.FILLED) {
                    log.info(EventLog.event(EventLog.ORDER_FILLED,
                                    "orderId", resting.orderId(),
                                    "userId",  resting.userId(),
                                    "symbol",  cmd.symbol(),
                                    "qty",     resting.quantity()),
                            "resting order filled");
                }

                incomingFilled = newIncomingFilled;
                incomingRemaining -= tradeQty;
            }

            if (cmd.type() == OrderType.MARKET && incomingRemaining > 0) {
                orders.markRejected(orderId, INSUFFICIENT_LIQUIDITY);
                outbox.emit("orders:" + cmd.userId(),
                        orderJson(orderId, OrderStatus.CANCELLED, incomingFilled));

                meters.counter("orders_rejected_total", "reason", INSUFFICIENT_LIQUIDITY).increment();

                log.info(EventLog.event(EventLog.ORDER_REJECTED,
                                "orderId", orderId,
                                "userId",  cmd.userId(),
                                "symbol",  cmd.symbol(),
                                "reason",  INSUFFICIENT_LIQUIDITY,
                                "filled",  incomingFilled),
                        "MARKET order rejected — book ran out");
                return new OrderResult(orderId, OrderStatus.CANCELLED,
                        incomingFilled, INSUFFICIENT_LIQUIDITY, false);
            }

            OrderStatus finalStatus;
            if (incomingFilled == 0)                       finalStatus = OrderStatus.OPEN;
            else if (incomingFilled == cmd.quantity())     finalStatus = OrderStatus.FILLED;
            else                                           finalStatus = OrderStatus.PARTIAL;

            return new OrderResult(orderId, finalStatus, incomingFilled, null, false);
        } finally {
            matchTimer.stop(meters.timer("match_duration_seconds", "symbol", cmd.symbol()));
        }
    }

    /**
     * Cancel an OPEN/PARTIAL order. Ownership is enforced. The cancel takes
     * the same per-symbol advisory lock as the match, so a cancel cannot race
     * a fill in flight on the same order:
     * <ul>
     *   <li>If the fill commits first, the cancel sees a terminal status and 409s.</li>
     *   <li>If the cancel commits first, the next match scan won't see the order.</li>
     * </ul>
     */
    @Transactional
    public CancelResult cancel(CancelOrderCommand cmd) {
        // Look up the symbol first — we need it to take the right lock.
        // The symbol column is immutable post-insert, so no lock is needed for this read.
        OrderRow firstRead = orders.findById(cmd.orderId())
                .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));
        if (!firstRead.userId().equals(cmd.userId())) {
            throw new AccessDeniedException("orderId " + cmd.orderId() + " does not belong to caller");
        }

        locks.lockForSymbol(firstRead.symbol());

        // Re-fetch under the lock — status may have changed between the lookup
        // and the lock acquisition (a fill could have completed on another node).
        OrderRow current = orders.findById(cmd.orderId())
                .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));

        if (!current.status().isCancellable()) {
            throw new IllegalStateException(
                    "order is " + current.status() + " and cannot be cancelled");
        }

        orders.markCancelled(cmd.orderId());
        outbox.emit("book:"   + current.symbol(), bookJson(current.symbol()));
        outbox.emit("orders:" + current.userId(),
                orderJson(current.orderId(), OrderStatus.CANCELLED, current.filledQty()));

        log.info(EventLog.event(EventLog.ORDER_CANCELLED,
                        "orderId", cmd.orderId(),
                        "userId",  current.userId(),
                        "symbol",  current.symbol(),
                        "filled",  current.filledQty()),
                "order cancelled");

        return new CancelResult(cmd.orderId(), OrderStatus.CANCELLED, current.filledQty());
    }

    // -------- minimal outbox payload helpers (Phase 7 will replace with proper Jackson serialisation) --------

    private static String tradeJson(UUID tradeId, BigDecimal price, long qty) {
        return String.format(
                "{\"event\":\"TRADE\",\"tradeId\":\"%s\",\"price\":\"%s\",\"qty\":%d}",
                tradeId, price.toPlainString(), qty);
    }

    private static String orderJson(UUID orderId, OrderStatus status, long filledQty) {
        return String.format(
                "{\"event\":\"ORDER\",\"orderId\":\"%s\",\"status\":\"%s\",\"filledQty\":%d}",
                orderId, status.name(), filledQty);
    }

    private static String bookJson(String symbol) {
        return String.format("{\"event\":\"BOOK_UPDATE\",\"symbol\":\"%s\"}", symbol);
    }
}
