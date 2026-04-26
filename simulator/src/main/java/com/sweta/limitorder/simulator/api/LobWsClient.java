package com.sweta.limitorder.simulator.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Plan §2.4 — WebSocket client for the simulator.
 *
 * <p>Wraps {@link java.net.http.WebSocket}; one client per run, opens
 * fresh sessions per channel. The JWT travels on the upgrade
 * {@code Authorization: Bearer …} header (matching the
 * {@code JwtHandshakeInterceptor}'s primary path; the {@code ?token=}
 * query-param fallback is for browsers, which can't set headers on
 * the WS upgrade).
 *
 * <p>Each {@link Session} exposes a {@link BlockingQueue} of raw text
 * frames so callers can poll synchronously — appropriate for the
 * simulator's check-mode style ("read snapshot, then read N deltas,
 * then assert"). For richer call patterns we'd promote this to a
 * reactive Flux, but Phase 2 doesn't need it.
 */
public class LobWsClient {

    private final String baseWsUrl;
    private final HttpClient http;

    public LobWsClient(String baseHttpUrl) {
        // ws:// over http://, wss:// over https:// — flips the scheme
        // without otherwise touching the host/port the user supplied.
        this(baseHttpUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public LobWsClient(String baseHttpUrl, HttpClient http) {
        this.baseWsUrl = httpToWs(baseHttpUrl);
        this.http = http;
    }

    public Session subscribeBook(String symbol, JwtToken token) {
        return open(URI.create(baseWsUrl + "/ws/book/" + symbol), token);
    }

    public Session subscribeOrders(JwtToken token) {
        return open(URI.create(baseWsUrl + "/ws/orders/mine"), token);
    }

    private Session open(URI uri, JwtToken token) {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        QueueingListener listener = new QueueingListener(frames);
        WebSocket ws = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token.token())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, listener)
                .join();
        return new Session(ws, frames, listener);
    }

    /** Test seam — direct construction lets the listener be exercised without a real WS. */
    static QueueingListener newListenerFor(BlockingQueue<String> frames) {
        return new QueueingListener(frames);
    }

    private static String httpToWs(String httpUrl) {
        if (httpUrl.startsWith("https://")) return "wss://" + httpUrl.substring("https://".length());
        if (httpUrl.startsWith("http://")) return "ws://" + httpUrl.substring("http://".length());
        if (httpUrl.startsWith("wss://") || httpUrl.startsWith("ws://")) return httpUrl;
        throw new IllegalArgumentException("Unsupported scheme in baseUrl: " + httpUrl);
    }

    /**
     * Open WebSocket session with a queue of complete text frames and
     * a way to close cleanly. Test-only fields are package-private.
     */
    public static final class Session implements AutoCloseable {

        public final WebSocket socket;
        public final BlockingQueue<String> frames;
        final QueueingListener listener;

        Session(WebSocket socket, BlockingQueue<String> frames, QueueingListener listener) {
            this.socket = socket;
            this.frames = frames;
            this.listener = listener;
        }

        public boolean isClosed() {
            return listener.closed;
        }

        @Override
        public void close() {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
            }
        }
    }

    /**
     * Buffers complete text frames into a {@link BlockingQueue}. The JDK
     * {@link WebSocket.Listener} can deliver a frame in multiple
     * partial calls (when {@code last == false}); we accumulate via a
     * {@link StringBuilder} and only push the joined frame when
     * {@code last == true}.
     */
    static final class QueueingListener implements WebSocket.Listener {

        private final BlockingQueue<String> queue;
        private final StringBuilder partial = new StringBuilder();
        volatile boolean closed = false;
        volatile Throwable error;

        QueueingListener(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                queue.offer(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error = error;
            this.closed = true;
        }
    }
}
