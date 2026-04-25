package com.sweta.limitorder.ws;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test-only WebSocket client that queues every text frame so assertions
 * can wait on them with a timeout.
 */
final class CapturingWsClient extends TextWebSocketHandler {

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        messages.add(message.getPayload());
    }

    /** Block up to {@code timeoutSeconds} for the next frame; null if timeout. */
    String poll(int timeoutSeconds) throws InterruptedException {
        return messages.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    int size() {
        return messages.size();
    }
}
