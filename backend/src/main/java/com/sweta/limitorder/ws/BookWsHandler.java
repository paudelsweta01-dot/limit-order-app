package com.sweta.limitorder.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweta.limitorder.book.BookQueryService;
import com.sweta.limitorder.book.BookSnapshot;
import com.sweta.limitorder.persistence.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;

/**
 * {@code /ws/book/{symbol}}.
 *
 * <p>On connect: snapshot the book (top-5 levels, last trade, cursor — see
 * {@link BookQueryService#snapshot}), send it as the first frame, then
 * subscribe the session to two channels: {@code book:{symbol}} (level
 * updates) and {@code trades:{symbol}} (executed trades). Phase 7's
 * {@link com.sweta.limitorder.outbox.OutboxFanout} drives those channels.
 *
 * <p>On disconnect: unsubscribe from every channel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookWsHandler extends TextWebSocketHandler {

    private final BookQueryService books;
    private final SymbolRepository symbols;
    private final InMemoryWsBroker broker;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String symbol = extractSymbol(session.getUri());
        if (symbol == null || !symbols.existsById(symbol)) {
            session.close(CloseStatus.BAD_DATA.withReason("unknown symbol"));
            return;
        }

        BookSnapshot snapshot = books.snapshot(symbol);
        broker.sendSnapshot(session, "book:" + symbol, snapshot.cursor(),
                json.writeValueAsString(snapshot));

        broker.subscribe("book:"   + symbol, session);
        broker.subscribe("trades:" + symbol, session);
        log.debug("ws connected sessionId={} symbol={}", session.getId(), symbol);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broker.unsubscribeAll(session);
        log.debug("ws closed sessionId={} status={}", session.getId(), status);
    }

    /**
     * Pulls the trailing path segment from a URI like {@code /ws/book/AAPL}.
     * Returns {@code null} if the URI doesn't have the expected shape.
     */
    private static String extractSymbol(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        int last = path.lastIndexOf('/');
        if (last < 0 || last == path.length() - 1) return null;
        return path.substring(last + 1);
    }
}
