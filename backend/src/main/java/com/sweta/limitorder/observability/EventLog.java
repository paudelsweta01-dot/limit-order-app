package com.sweta.limitorder.observability;

import net.logstash.logback.marker.LogstashMarker;
import net.logstash.logback.marker.Markers;

/**
 * Structured-logging helper. Bundles an event name plus arbitrary key/value
 * pairs into a single Logstash marker so they land as <em>first-class JSON
 * fields</em> on the wire, not embedded substrings inside {@code message}.
 *
 * <p>This is what makes {@code grep '"event":"ORDER_ACCEPTED"'} actually
 * work against captured logs (architecture §4.10 + Phase 9.1 acceptance).
 *
 * <p>Usage:
 * <pre>{@code
 * import static com.sweta.limitorder.observability.EventLog.event;
 *
 * log.info(event("ORDER_ACCEPTED",
 *         "orderId", orderId,
 *         "symbol",  cmd.symbol(),
 *         "side",    cmd.side(),
 *         "type",    cmd.type(),
 *         "price",   limitPrice,
 *         "qty",     cmd.quantity()),
 *     "order accepted");
 * }</pre>
 *
 * <p>Produces JSON like:
 * <pre>{@code
 * {"@timestamp":"…","level":"INFO","message":"order accepted",
 *  "event":"ORDER_ACCEPTED","orderId":"…","symbol":"AAPL",…}
 * }</pre>
 *
 * <p>Per-line MDC fields (instance, requestId, userId) are attached
 * separately by the encoder; callers don't need to thread them through.
 */
public final class EventLog {

    /** Standard event names. The set is closed — only these five may appear in {@code event}. */
    public static final String ORDER_ACCEPTED  = "ORDER_ACCEPTED";
    public static final String ORDER_REJECTED  = "ORDER_REJECTED";
    public static final String ORDER_FILLED    = "ORDER_FILLED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String TRADE_EXECUTED  = "TRADE_EXECUTED";

    private EventLog() {}

    /**
     * Build a marker carrying {@code event=<name>} plus the supplied key/value pairs.
     *
     * @param eventName one of {@link #ORDER_ACCEPTED} / {@link #ORDER_REJECTED} /
     *                  {@link #ORDER_FILLED} / {@link #ORDER_CANCELLED} /
     *                  {@link #TRADE_EXECUTED}.
     * @param kvs       alternating key/value pairs ({@code key1, value1, key2, value2, ...}).
     */
    public static LogstashMarker event(String eventName, Object... kvs) {
        if (kvs.length % 2 != 0) {
            throw new IllegalArgumentException("kvs must be in (key, value) pairs; got " + kvs.length);
        }
        LogstashMarker marker = Markers.append("event", eventName);
        for (int i = 0; i < kvs.length; i += 2) {
            String key = (String) kvs[i];
            Object value = kvs[i + 1];
            marker = marker.and(Markers.append(key, value));
        }
        return marker;
    }
}
