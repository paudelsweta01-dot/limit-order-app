package com.sweta.limitorder.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 7 default {@link WsBroker} — logs every publish at DEBUG so the
 * outbox + LISTEN/NOTIFY pipeline can be observed end-to-end before the
 * Phase 8 WebSocket session registry lands. Phase 8 will replace this
 * with the real {@code InMemoryWsBroker} that pushes to subscribed
 * {@code WebSocketSession}s.
 */
@Component
@Slf4j
class LoggingWsBroker implements WsBroker {

    @Override
    public void publish(String channel, String payload, long cursor) {
        log.debug("event=WS_PUBLISH channel={} cursor={} payload={}",
                channel, cursor, payload);
    }
}
