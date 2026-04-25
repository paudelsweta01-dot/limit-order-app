package com.sweta.limitorder.ws;

/**
 * Wire envelope for WebSocket frames sent to clients.
 *
 * <pre>
 *   { "type": "snapshot" | "delta",
 *     "channel": "book:AAPL",
 *     "cursor": 12345,
 *     "payload": &lt;arbitrary JSON&gt; }
 * </pre>
 *
 * <p>Channel names and cursor are server-controlled (no risky chars), so
 * string interpolation is safe. The payload is already a JSON document
 * (either freshly serialised by Jackson for snapshots, or a verbatim
 * outbox payload for deltas), so it's spliced in unquoted.
 */
final class WsFrame {

    private WsFrame() {}

    static String snapshot(String channel, long cursor, String payloadJson) {
        return frame("snapshot", channel, cursor, payloadJson);
    }

    static String delta(String channel, long cursor, String payloadJson) {
        return frame("delta", channel, cursor, payloadJson);
    }

    private static String frame(String type, String channel, long cursor, String payloadJson) {
        return "{\"type\":\"" + type
                + "\",\"channel\":\"" + channel
                + "\",\"cursor\":" + cursor
                + ",\"payload\":" + payloadJson + "}";
    }
}
