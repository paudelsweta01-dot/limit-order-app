package com.sweta.limitorder.simulator.mode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sweta.limitorder.simulator.api.JwtToken;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.LobApiException;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.api.dto.BookLevel;
import com.sweta.limitorder.simulator.api.dto.BookSnapshot;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;
import com.sweta.limitorder.simulator.api.dto.SymbolResponse;
import com.sweta.limitorder.simulator.report.AssertionResult;
import com.sweta.limitorder.simulator.report.RunReport;

/**
 * Plan §6 — drive load against two backend nodes simultaneously, then
 * assert their books converge (architecture §3 NFR: cross-node visibility
 * within 1 s).
 *
 * <p>The run alternates submissions per worker (workers with even idx
 * post to node A, odd idx to node B). After {@code duration} elapses
 * we wait {@link #CONVERGENCE_WINDOW} for the outbox-LISTEN-NOTIFY
 * fan-out to settle, then snapshot every symbol's book on each node
 * and compare them after canonical ordering. The same data via the
 * two LBs MUST be byte-identical — that's the architecture-level
 * promise.
 */
public class MultiInstanceRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MultiInstanceRunner.class);
    /** Plan §6.3 — wait "up to 1s" for cross-node convergence after load ends. */
    static final Duration CONVERGENCE_WINDOW = Duration.ofMillis(1_500);

    private final LobApiClient nodeA;
    private final LobApiClient nodeB;
    private final TokenCache tokens;
    private final SeedCredentials creds;
    private final List<String> usernames;
    private final int users;
    private final int rate;
    private final Duration duration;
    private final long seed;

    /** Plan §9.2 — flipped by {@link #requestStop()} (e.g. from a SIGTERM
     *  shutdown hook) to exit worker loops mid-run so the convergence
     *  check + report still get to run. */
    private final AtomicBoolean stop = new AtomicBoolean(false);

    /** Plan §9.2 — request a graceful stop; main thread continues into
     *  the convergence check + report write. */
    public void requestStop() {
        stop.set(true);
    }

    public MultiInstanceRunner(LobApiClient nodeA, LobApiClient nodeB,
                               TokenCache tokens, SeedCredentials creds,
                               List<String> usernames,
                               int users, int rate, Duration duration, long seed) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.tokens = tokens;
        this.creds = creds;
        this.usernames = List.copyOf(usernames);
        this.users = users;
        this.rate = rate;
        this.duration = duration;
        this.seed = seed;
    }

    public RunReport run(String runId) {
        RunReport report = new RunReport(runId, "multi-instance", Instant.now());

        // Plan §6.1 — fail fast if either node is unreachable / mis-configured.
        List<SymbolResponse> symbols;
        try {
            String firstUser = usernames.get(0);
            JwtToken tokenA = tokens.getOrLogin(firstUser, creds.passwordFor(firstUser), nodeA);
            symbols = nodeA.getSymbols(tokenA);
            // Probe node B with a getSymbols call too — a bad URL fails here.
            // Use a separate cache slot keyed by "user@nodeB" so we don't
            // pollute the run-time TokenCache (different node = different
            // session per the architecture).
            nodeB.login(firstUser, creds.passwordFor(firstUser));
        } catch (LobApiException e) {
            report.addAssertion(AssertionResult.fail("multi-instance:bootstrap",
                    List.of("failed to reach both nodes: " + e.getMessage())));
            report.finishedAt = Instant.now();
            return report;
        }

        Map<String, BigDecimal> refPrices = new HashMap<>();
        symbols.forEach(s -> refPrices.put(s.symbol(), s.refPrice()));
        List<String> symbolNames = symbols.stream().map(SymbolResponse::symbol).toList();
        double meanInterArrivalMs = 1000.0 * users / Math.max(1, rate);

        // Run two-node load in parallel.
        stop.set(false);
        LoadStats stats = new LoadStats();
        ExecutorService workers = Executors.newFixedThreadPool(users, r -> {
            Thread t = new Thread(r);
            t.setName("multi-load-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        Instant runEnd = Instant.now().plus(duration);
        for (int i = 0; i < users; i++) {
            final int idx = i;
            // Even idx → node A, odd idx → node B (plan §6.2 round-robin).
            workers.submit(() -> workerLoop(idx, symbolNames, refPrices,
                    meanInterArrivalMs, runEnd, stop, stats,
                    idx % 2 == 0 ? nodeA : nodeB,
                    idx % 2 == 0 ? "A" : "B"));
        }

        // Plan §9.2 — wait for runEnd OR external stop request, polling
        // every 100 ms so SIGTERM is responsive.
        while (!stop.get() && Instant.now().isBefore(runEnd)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop.set(true);
        workers.shutdown();
        try { workers.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        report.submitted = (int) stats.submitted();
        report.accepted = (int) stats.accepted();
        report.rejected = (int) stats.rejected();

        // Plan §6.3 — wait up to 1.5s for cross-node convergence, then snapshot.
        try { Thread.sleep(CONVERGENCE_WINDOW.toMillis()); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        report.addAssertion(checkBooksConverge(symbolNames));
        report.finishedAt = Instant.now();
        return report;
    }

    // ---------- worker ----------

    private void workerLoop(int idx, List<String> symbolNames,
                            Map<String, BigDecimal> refPrices,
                            double meanInterArrivalMs, Instant runEnd,
                            AtomicBoolean stop, LoadStats stats,
                            LobApiClient client, String nodeLabel) {
        String username = usernames.get(idx % usernames.size());
        Random rng = new Random(seed + idx);
        OrderGenerator gen = new OrderGenerator(symbolNames, refPrices, rng);

        // Each node has its own TokenCache slot — re-using the parent
        // cache here would be wrong because tokens issued by node A
        // shouldn't be replayed at node B (separate signing-secret
        // domains in production). Cheap workaround: log in once per
        // worker per node (no cross-thread sharing).
        JwtToken token;
        try {
            token = client.login(username, creds.passwordFor(username));
        } catch (LobApiException e) {
            LOG.warn("multi worker {} login failed on node {}: {}", idx, nodeLabel, e.getMessage());
            return;
        }

        Random sleepRng = new Random(seed + 2_000_000L + idx);
        while (!stop.get() && Instant.now().isBefore(runEnd)) {
            SubmitOrderRequest req = gen.next();
            long startNanos = System.nanoTime();
            stats.recordSubmitted();
            try {
                var res = client.submit(req, token);
                stats.recordLatencyNanos(System.nanoTime() - startNanos);
                if (res.status() != null) {
                    switch (res.status()) {
                        case OPEN, PARTIAL, FILLED -> stats.recordAccepted();
                        case CANCELLED, REJECTED -> stats.recordRejected();
                    }
                }
            } catch (LobApiException e) {
                stats.recordLatencyNanos(System.nanoTime() - startNanos);
                stats.recordRejected();
            }
            double u = sleepRng.nextDouble();
            long sleepMs = (long) (-meanInterArrivalMs * Math.log(Math.max(u, 1e-9)));
            try { Thread.sleep(Math.max(0, sleepMs)); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---------- post-run book equality ----------

    private AssertionResult checkBooksConverge(List<String> symbols) {
        // Each node enforces auth on /api/book — log in once on each.
        String reader = usernames.get(0);
        JwtToken tokenA, tokenB;
        try {
            tokenA = nodeA.login(reader, creds.passwordFor(reader));
            tokenB = nodeB.login(reader, creds.passwordFor(reader));
        } catch (LobApiException e) {
            return AssertionResult.fail("books-equal-across-nodes",
                    List.of("failed to obtain reader tokens: " + e.getMessage()));
        }

        List<String> diffs = new ArrayList<>();
        for (String symbol : symbols) {
            BookSnapshot a, b;
            try {
                a = nodeA.getBook(symbol, tokenA);
                b = nodeB.getBook(symbol, tokenB);
            } catch (LobApiException e) {
                diffs.add(symbol + ": failed to fetch book on one side: " + e.getMessage());
                continue;
            }
            diffs.addAll(diffLevels(symbol, "bids", a.bids(), b.bids()));
            diffs.addAll(diffLevels(symbol, "asks", a.asks(), b.asks()));
        }
        return diffs.isEmpty()
                ? AssertionResult.pass("books-equal-across-nodes")
                : AssertionResult.fail("books-equal-across-nodes", diffs);
    }

    /**
     * Backend already returns top-5 levels sorted by price (descending
     * for bids, ascending for asks); that ordering is the canonical
     * form, so we compare position-by-position.
     */
    private static List<String> diffLevels(String symbol, String side,
                                            List<BookLevel> a, List<BookLevel> b) {
        List<String> diffs = new ArrayList<>();
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            BookLevel la = i < a.size() ? a.get(i) : null;
            BookLevel lb = i < b.size() ? b.get(i) : null;
            if (!equal(la, lb)) {
                diffs.add(symbol + "." + side + "[" + i + "]: nodeA=" + render(la)
                        + " nodeB=" + render(lb));
            }
        }
        return diffs;
    }

    private static boolean equal(BookLevel x, BookLevel y) {
        if (x == null && y == null) return true;
        if (x == null || y == null) return false;
        return x.price().compareTo(y.price()) == 0
                && x.qty() == y.qty()
                && x.userCount() == y.userCount();
    }

    private static String render(BookLevel l) {
        return l == null
                ? "(empty)"
                : "price=" + l.price().toPlainString() + " qty=" + l.qty() + " userCount=" + l.userCount();
    }
}
