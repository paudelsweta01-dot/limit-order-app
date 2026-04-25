package com.sweta.limitorder.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweta.limitorder.orders.MyOrdersSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * {@code /ws/orders/mine}.
 *
 * <p>On connect: snapshot the authenticated user's orders + outbox cursor
 * (REPEATABLE READ tx — see {@link MyOrdersSnapshotService}), send as the
 * first frame, then subscribe to {@code orders:{userId}}. The matching
 * engine emits to that channel for every fill/cancel/reject affecting an
 * order owned by the user (architecture §4.4 outbox writes).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrdersWsHandler extends TextWebSocketHandler {

    private final MyOrdersSnapshotService snapshots;
    private final InMemoryWsBroker broker;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID userId = (UUID) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("missing principal"));
            return;
        }

        MyOrdersSnapshotService.Snapshot snap = snapshots.snapshot(userId);
        broker.sendSnapshot(session, "orders:" + userId, snap.cursor(),
                json.writeValueAsString(snap.orders()));

        broker.subscribe("orders:" + userId, session);
        log.debug("ws connected sessionId={} userId={}", session.getId(), userId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broker.unsubscribeAll(session);
        log.debug("ws closed sessionId={} status={}", session.getId(), status);
    }
}
