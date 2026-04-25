package com.sweta.limitorder.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process subscription registry plus broadcast.
 *
 * <p>Each WebSocketSession can subscribe to N channels (e.g. one BookWsHandler
 * subscription registers under both {@code book:AAPL} and {@code trades:AAPL}).
 * The {@link OutboxFanout} drives {@link #publish} from the listener thread;
 * WebSocket handlers register / unregister from request threads. Both
 * read/write the same map concurrently, so the underlying maps are concurrent.
 *
 * <p>{@code WebSocketSession.sendMessage} is not safe under concurrent senders
 * (snapshot from the handler thread vs. delta from the listener thread) — we
 * synchronise on the session for ordered byte-level writes.
 */
@Component
@Slf4j
public class InMemoryWsBroker implements WsBroker {

    private final ConcurrentMap<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();

    public void subscribe(String channel, WebSocketSession session) {
        subscriptions
                .computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void unsubscribeAll(WebSocketSession session) {
        subscriptions.values().forEach(set -> set.remove(session));
    }

    @Override
    public void publish(String channel, String payload, long cursor) {
        Set<WebSocketSession> sessions = subscriptions.get(channel);
        if (sessions == null || sessions.isEmpty()) return;

        String envelope = WsFrame.delta(channel, cursor, payload);
        TextMessage message = new TextMessage(envelope);

        for (WebSocketSession session : sessions) {
            sendQuietly(session, message);
        }
    }

    /**
     * Send a snapshot frame on a single session — used by handlers when a
     * client first connects.
     */
    public void sendSnapshot(WebSocketSession session, String channel, long cursor, String payloadJson) {
        sendQuietly(session, new TextMessage(WsFrame.snapshot(channel, cursor, payloadJson)));
    }

    public int subscriberCount(String channel) {
        Set<WebSocketSession> set = subscriptions.get(channel);
        return set == null ? 0 : set.size();
    }

    private void sendQuietly(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) return;
        try {
            // Spring's WebSocketSession.sendMessage isn't documented as
            // thread-safe; serialise concurrent writes per-session here.
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.warn("ws send failed sessionId={} {}", session.getId(), e.getMessage());
        } catch (IllegalStateException e) {
            // Spring throws this if the session has just closed concurrently.
            log.debug("ws send raced with close sessionId={}", session.getId());
        }
    }
}
