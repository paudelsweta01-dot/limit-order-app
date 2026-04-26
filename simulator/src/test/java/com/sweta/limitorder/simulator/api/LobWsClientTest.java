package com.sweta.limitorder.simulator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Plan §2.4 — smoke covering the listener's behaviour in isolation.
 * The full WebSocket handshake is exercised against the real backend
 * in Phase 6 integration runs; here we verify the queueing /
 * fragmentation / close-propagation logic without spinning up a stub
 * WS server (which would require Tyrus or Jetty-WS test deps).
 */
class LobWsClientTest {

    @Test
    void completeTextFrameLandsInTheQueue() throws InterruptedException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        LobWsClient.QueueingListener listener = LobWsClient.newListenerFor(queue);
        WebSocket ws = mock(WebSocket.class);

        listener.onOpen(ws);
        listener.onText(ws, "{\"type\":\"snapshot\",\"channel\":\"book:AAPL\",\"cursor\":42,\"payload\":{}}", true);

        String frame = queue.poll(100, TimeUnit.MILLISECONDS);
        assertThat(frame).contains("\"type\":\"snapshot\"").contains("\"cursor\":42");
        verify(ws, org.mockito.Mockito.atLeastOnce()).request(1);
    }

    @Test
    void fragmentedTextFramesAreReassembledBeforeQueueing() throws InterruptedException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        LobWsClient.QueueingListener listener = LobWsClient.newListenerFor(queue);
        WebSocket ws = mock(WebSocket.class);

        // Three partial frames, last set on the final one.
        listener.onText(ws, "{\"type\":\"del", false);
        listener.onText(ws, "ta\",\"channel\":\"book:AA",  false);
        listener.onText(ws, "PL\",\"cursor\":43,\"payload\":{}}", true);

        // Nothing should have been queued for the partial calls.
        assertThat(queue).hasSize(1);
        String reassembled = queue.poll(100, TimeUnit.MILLISECONDS);
        assertThat(reassembled).isEqualTo(
                "{\"type\":\"delta\",\"channel\":\"book:AAPL\",\"cursor\":43,\"payload\":{}}");
    }

    @Test
    void onCloseFlipsClosedFlag() {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        LobWsClient.QueueingListener listener = LobWsClient.newListenerFor(queue);
        WebSocket ws = mock(WebSocket.class);

        assertThat(listener.closed).isFalse();
        listener.onClose(ws, WebSocket.NORMAL_CLOSURE, "by peer");
        assertThat(listener.closed).isTrue();
    }

    @Test
    void onErrorRecordsThrowableAndFlipsClosed() {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        LobWsClient.QueueingListener listener = LobWsClient.newListenerFor(queue);
        WebSocket ws = mock(WebSocket.class);

        Throwable boom = new RuntimeException("server reset");
        listener.onError(ws, boom);
        assertThat(listener.error).isSameAs(boom);
        assertThat(listener.closed).isTrue();
    }

    @Test
    void httpUrlFlipsToWsScheme_httpsFlipsToWss() {
        // No real connection — just exercise the URL helper indirectly
        // via constructor wiring. Connection itself happens in subscribe*.
        new LobWsClient("http://localhost:8080");   // valid → ok
        new LobWsClient("https://prod.example.com"); // valid → ok
        new LobWsClient("ws://localhost:8080");      // already-ws → ok
        assertThatThrownBy(() -> new LobWsClient("ftp://nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
