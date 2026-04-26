package com.sweta.limitorder.simulator.api;

import com.sweta.limitorder.simulator.api.dto.ErrorResponse;

/**
 * Thrown by {@link LobApiClient} for any non-2xx response. Carries the
 * HTTP status and the deserialised §4.11 error envelope so callers can
 * branch on {@code envelope.code()} without parsing the body again.
 *
 * <p>The envelope can be {@code null} if the server returned a non-JSON
 * 5xx (e.g. a 502 from nginx with a stock HTML page) — callers should
 * defend against that.
 */
public class LobApiException extends RuntimeException {

    private final int status;
    private final ErrorResponse envelope;

    public LobApiException(int status, ErrorResponse envelope, String message) {
        super(message);
        this.status = status;
        this.envelope = envelope;
    }

    public int status() {
        return status;
    }

    /** May be null if the response body wasn't a parseable §4.11 envelope. */
    public ErrorResponse envelope() {
        return envelope;
    }

    @Override
    public String toString() {
        return "LobApiException{status=" + status
                + ", code=" + (envelope != null ? envelope.code() : "?")
                + ", message=" + getMessage() + "}";
    }
}
