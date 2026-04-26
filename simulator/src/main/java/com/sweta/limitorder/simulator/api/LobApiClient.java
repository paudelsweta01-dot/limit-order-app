package com.sweta.limitorder.simulator.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sweta.limitorder.simulator.api.dto.BookSnapshot;
import com.sweta.limitorder.simulator.api.dto.BookTotals;
import com.sweta.limitorder.simulator.api.dto.CancelOrderResponse;
import com.sweta.limitorder.simulator.api.dto.ErrorResponse;
import com.sweta.limitorder.simulator.api.dto.LoginRequest;
import com.sweta.limitorder.simulator.api.dto.LoginResponse;
import com.sweta.limitorder.simulator.api.dto.MyFillResponse;
import com.sweta.limitorder.simulator.api.dto.MyOrderResponse;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderResponse;
import com.sweta.limitorder.simulator.api.dto.SymbolResponse;

/**
 * Plan §2.1 / §2.3 — the simulator's HTTP-side mouth and ears.
 *
 * <p>Wraps {@link HttpClient}; one instance per run. Every method:
 * <ul>
 *   <li>Builds a request to {@code baseUrl + path}.</li>
 *   <li>Sends synchronously; deserialises the body via Jackson.</li>
 *   <li>On 2xx returns the typed payload.</li>
 *   <li>On 4xx throws {@link LobApiException} with the §4.11 envelope.
 *       4xx is never retried — the body's already valid JSON saying
 *       "this is your fault, fix the request".</li>
 *   <li>On 5xx (502/503/504) or a connect timeout, retries with
 *       exponential backoff up to {@link #MAX_ATTEMPTS}. Architecturally
 *       important: the same {@code clientOrderId} is reused across
 *       retries so the backend's idempotency (§4.6) collapses any
 *       duplicate POST onto the original order.</li>
 * </ul>
 */
public class LobApiClient {

    /** Plan §2.3: cap at 3 attempts. */
    static final int MAX_ATTEMPTS = 3;
    /** First retry backoff — multiplied per attempt (100ms, 200ms). */
    private static final Duration RETRY_BASE_BACKOFF = Duration.ofMillis(100);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public LobApiClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    /** Constructor taking an injected {@link HttpClient} — for tests. */
    public LobApiClient(String baseUrl, HttpClient http) {
        this(baseUrl, http, JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }

    public LobApiClient(String baseUrl, HttpClient http, ObjectMapper json) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.http = http;
        this.json = json;
    }

    // ---------- public API surface (plan §2.1) ----------

    public JwtToken login(String username, String password) {
        LoginRequest body = new LoginRequest(username, password);
        LoginResponse res = post("/api/auth/login", body, null, LoginResponse.class);
        return new JwtToken(res.token(), res.userId(), res.name());
    }

    public List<SymbolResponse> getSymbols(JwtToken auth) {
        return getList("/api/symbols", auth, new TypeReference<List<SymbolResponse>>() {});
    }

    public BookSnapshot getBook(String symbol) {
        return get("/api/book/" + encode(symbol), null, BookSnapshot.class);
    }

    public BookTotals getTotals(String symbol) {
        return get("/api/book/" + encode(symbol) + "/totals", null, BookTotals.class);
    }

    public SubmitOrderResponse submit(SubmitOrderRequest request, JwtToken auth) {
        return post("/api/orders", request, auth, SubmitOrderResponse.class);
    }

    public CancelOrderResponse cancel(UUID orderId, JwtToken auth) {
        return delete("/api/orders/" + orderId, auth, CancelOrderResponse.class);
    }

    public List<MyOrderResponse> getMyOrders(JwtToken auth) {
        return getList("/api/orders/mine", auth, new TypeReference<List<MyOrderResponse>>() {});
    }

    public List<MyFillResponse> getMyFills(JwtToken auth) {
        return getList("/api/fills/mine", auth, new TypeReference<List<MyFillResponse>>() {});
    }

    // ---------- low-level send + retry ----------

    private <T> T get(String path, JwtToken auth, Class<T> type) {
        return send(builder(path, auth).GET(), type);
    }

    private <T> T getList(String path, JwtToken auth, TypeReference<T> type) {
        return sendForType(builder(path, auth).GET(), type);
    }

    private <T> T post(String path, Object body, JwtToken auth, Class<T> type) {
        HttpRequest.Builder b = builder(path, auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        return send(b, type);
    }

    private <T> T delete(String path, JwtToken auth, Class<T> type) {
        return send(builder(path, auth).DELETE(), type);
    }

    private HttpRequest.Builder builder(String path, JwtToken auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (auth != null) b.header("Authorization", "Bearer " + auth.token());
        return b;
    }

    private <T> T send(HttpRequest.Builder rb, Class<T> type) {
        return parseSuccessful(execute(rb), type);
    }

    private <T> T sendForType(HttpRequest.Builder rb, TypeReference<T> type) {
        return parseSuccessfulType(execute(rb), type);
    }

    /**
     * Sends the request with retry-on-transient-error (plan §2.3). The
     * {@link HttpRequest.Builder} is rebuilt-from-immutable each
     * attempt because java.net.http requests are one-shot.
     */
    private HttpResponse<String> execute(HttpRequest.Builder rb) {
        HttpRequest request = rb.build();
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
                int status = response.statusCode();
                if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
                    sleep(backoff(attempt));
                    continue;
                }
                return response;
            } catch (IOException ioe) {
                lastIo = ioe;
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff(attempt));
                    continue;
                }
                throw new LobApiException(0, null,
                        "network error after " + attempt + " attempts: " + ioe.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LobApiException(0, null, "interrupted: " + ie.getMessage());
            }
        }
        // Defensive — loop above always returns or throws.
        throw new LobApiException(0, null,
                "unreachable retry tail; last IOException: " + (lastIo != null ? lastIo.getMessage() : "?"));
    }

    private static boolean isRetryable(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static Duration backoff(int attempt) {
        return RETRY_BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- response parsing ----------

    private <T> T parseSuccessful(HttpResponse<String> r, Class<T> type) {
        if (r.statusCode() / 100 == 2) {
            try {
                if (type == Void.class || r.body().isEmpty()) return null;
                return json.readValue(r.body(), type);
            } catch (IOException e) {
                throw new LobApiException(r.statusCode(), null,
                        "failed to deserialise success body: " + e.getMessage());
            }
        }
        throw apiException(r);
    }

    private <T> T parseSuccessfulType(HttpResponse<String> r, TypeReference<T> type) {
        if (r.statusCode() / 100 == 2) {
            try {
                return json.readValue(r.body(), type);
            } catch (IOException e) {
                throw new LobApiException(r.statusCode(), null,
                        "failed to deserialise success body: " + e.getMessage());
            }
        }
        throw apiException(r);
    }

    private LobApiException apiException(HttpResponse<String> r) {
        ErrorResponse env = null;
        try {
            if (!r.body().isEmpty()) env = json.readValue(r.body(), ErrorResponse.class);
        } catch (JsonMappingException jme) {
            // Body wasn't a §4.11 envelope (e.g. nginx 502 HTML) — leave null.
        } catch (IOException ignored) {
            // ditto
        }
        String msg = env != null && env.message() != null
                ? env.message()
                : "HTTP " + r.statusCode();
        return new LobApiException(r.statusCode(), env, msg);
    }

    // ---------- helpers ----------

    private String toJson(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new LobApiException(0, null, "failed to serialise request body: " + e.getMessage());
        }
    }

    private static String encode(String segment) {
        return segment.replace(" ", "%20"); // path segments here are symbols/UUIDs, no spaces in practice
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
