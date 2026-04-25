package com.sweta.limitorder.ws;

/**
 * Single seam between the cross-node outbox fan-out (Phase 7) and the
 * in-process WebSocket session registry (Phase 8).
 *
 * <p>Implementations decide what to do with a published payload — the
 * Phase 7 default just logs it. Phase 8 will provide the real
 * {@code InMemoryWsBroker} that writes to subscribed {@code WebSocketSession}s.
 */
public interface WsBroker {

    /**
     * Publish a payload to every client subscribed to the channel.
     *
     * @param channel routing key (e.g. {@code book:AAPL}, {@code trades:AAPL},
     *                {@code orders:{userId}})
     * @param payload JSON body, delivered to clients verbatim
     * @param cursor  outbox row id; clients drop deltas whose cursor is at
     *                or below the cursor in the snapshot they last consumed
     *                (snapshot/event race fix — architecture §4.8)
     */
    void publish(String channel, String payload, long cursor);
}
