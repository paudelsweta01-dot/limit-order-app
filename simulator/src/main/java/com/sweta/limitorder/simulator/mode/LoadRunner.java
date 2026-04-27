package com.sweta.limitorder.simulator.mode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sweta.limitorder.simulator.api.JwtToken;
import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.LobApiException;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest;
import com.sweta.limitorder.simulator.api.dto.SubmitOrderResponse;
import com.sweta.limitorder.simulator.api.dto.SymbolResponse;
import com.sweta.limitorder.simulator.report.AssertionResult;
import com.sweta.limitorder.simulator.report.RunReport;

/**
 * Plan §5 — load mode. Spawns N user threads; each loops with
 * exponential inter-arrival timing so aggregate submission rate
 * approaches {@code rate} ops/sec (Poisson process — burstier than a
 * metronome, more realistic). Stops on {@code duration} elapse.
 *
 * <p>Java 17 is the language floor for this project; we use a fixed
 * platform-thread pool. Java 21 virtual threads would be a one-line
 * swap once the project moves up.
 */
public class LoadRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LoadRunner.class);

    private final LobApiClient api;
    private final TokenCache tokens;
    private final SeedCredentials creds;
    private final List<String> usernames;
    private final int users;
    private final int rate;
    private final Duration duration;
    private final long seed;

    /** Plan §9.2 — flipped by {@link #requestStop()} (e.g. from a SIGTERM
     *  shutdown hook) to break worker loops mid-run so the post-loop
     *  reporting code still runs and emits a complete report. */
    private final AtomicBoolean stop = new AtomicBoolean(false);

    public LoadRunner(LobApiClient api, TokenCache tokens, SeedCredentials creds,
                      List<String> usernames, int users, int rate,
                      Duration duration, long seed) {
        this.api = api;
        this.tokens = tokens;
        this.creds = creds;
        this.usernames = List.copyOf(usernames);
        this.users = users;
        this.rate = rate;
        this.duration = duration;
        this.seed = seed;
    }

    /**
     * Plan §9.2 — request a graceful stop. Workers' main loops check
     * {@code stop} once per iteration and exit; main blocks in
     * {@link #run} draining the executor and writing the report, so the
     * caller (or its SIGTERM handler) gets a complete report back.
     */
    public void requestStop() {
        stop.set(true);
    }

    public RunReport run(String runId) {
        RunReport report = new RunReport(runId, "load", Instant.now());
        stop.set(false);

        // 1. Discover symbols + refPrices. Uses any seed user's token —
        //    /api/symbols is auth-required but the data is the same for all.
        List<SymbolResponse> symbols;
        try {
            String firstUser = usernames.get(0);
            JwtToken token = tokens.getOrLogin(firstUser, creds.passwordFor(firstUser), api);
            symbols = api.getSymbols(token);
        } catch (LobApiException e) {
            report.addAssertion(AssertionResult.fail("load:bootstrap",
                    List.of("failed to fetch /api/symbols: " + e.getMessage())));
            report.finishedAt = Instant.now();
            return report;
        }

        Map<String, BigDecimal> refPrices = new HashMap<>();
        symbols.forEach(s -> refPrices.put(s.symbol(), s.refPrice()));
        List<String> symbolNames = symbols.stream().map(SymbolResponse::symbol).toList();

        // 2. Per-user inter-arrival mean. Aggregate rate r = u * (1 / mean_per_user)
        //    so mean_per_user = u / r.
        double meanInterArrivalMs = 1000.0 * users / Math.max(1, rate);

        // 3. Run.
        LoadStats stats = new LoadStats();
        // Plan §9.1 — backpressure. Workers multiply their inter-arrival mean
        // by `throttleFactor` before sleeping, so a factor of 2.0 halves the
        // submission rate. Driven from the metrics tick below.
        AtomicReference<Double> throttleFactor = new AtomicReference<>(1.0);
        AtomicLong baselineP99Nanos = new AtomicLong(0);

        ExecutorService workers = Executors.newFixedThreadPool(users,
                runnable -> {
                    Thread t = new Thread(runnable);
                    t.setName("load-worker-" + t.getId());
                    t.setDaemon(true);
                    return t;
                });
        ScheduledExecutorService metricsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-metrics");
            t.setDaemon(true);
            return t;
        });
        // Plan §5.5 — 5s progress logs. Plan §9.1 — same tick evaluates
        // backpressure: latch throttle when p99 doubles vs the first
        // observed window's p99 OR the window saw any 503s; release as
        // soon as both clear.
        metricsTimer.scheduleAtFixedRate(() -> {
            LoadStats.Snapshot snap = stats.snapshotAndReset();
            LOG.info(snap.formatLine());
            evaluateBackpressure(snap, baselineP99Nanos, throttleFactor);
        }, 5, 5, TimeUnit.SECONDS);

        Instant runEnd = Instant.now().plus(duration);
        for (int i = 0; i < users; i++) {
            final int workerIdx = i;
            workers.submit(() -> workerLoop(workerIdx, symbolNames, refPrices,
                    meanInterArrivalMs, runEnd, stop, stats, throttleFactor));
        }

        // 4. Wait for runEnd OR an external stop request (plan §9.2 —
        //    SIGTERM handler calls requestStop()). 100 ms tick is small
        //    enough to feel responsive on Ctrl-C, large enough to be
        //    cheap on long runs.
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
        metricsTimer.shutdown();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        report.submitted = (int) stats.submitted();
        report.accepted = (int) stats.accepted();
        report.rejected = (int) stats.rejected();
        report.addAssertion(AssertionResult.pass("load:duration-elapsed"));
        report.finishedAt = Instant.now();
        LoadStats.Snapshot finalSnap = stats.snapshotAndReset();
        LOG.info("final " + finalSnap.formatLine());
        return report;
    }

    /**
     * Per-user worker. Cycles through the user list so each thread
     * authenticates as a distinct user (round-robin user assignment
     * across threads).
     */
    private void workerLoop(int workerIdx, List<String> symbolNames,
                            Map<String, BigDecimal> refPrices,
                            double meanInterArrivalMs, Instant runEnd,
                            AtomicBoolean stop, LoadStats stats,
                            AtomicReference<Double> throttleFactor) {
        String username = usernames.get(workerIdx % usernames.size());
        // Per-thread seeded RNG so the run is reproducible per worker.
        Random rng = new Random(seed + workerIdx);
        OrderGenerator gen = new OrderGenerator(symbolNames, refPrices, rng);

        JwtToken token;
        try {
            token = tokens.getOrLogin(username, creds.passwordFor(username), api);
        } catch (LobApiException e) {
            LOG.warn("load worker {} login failed for {}: {}", workerIdx, username, e.getMessage());
            return;
        }

        Random sleepRng = new Random(seed + 1_000_000L + workerIdx);
        while (!stop.get() && Instant.now().isBefore(runEnd)) {
            SubmitOrderRequest req = gen.next();
            long startNanos = System.nanoTime();
            stats.recordSubmitted();
            try {
                SubmitOrderResponse res = api.submit(req, token);
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
                if (e.status() == 503) stats.recordServer503();
            }

            // Exponential inter-arrival: -mean * ln(uniform(0,1)).
            // Plan §9.1 — multiply by throttleFactor (1.0 nominal, 2.0
            // halved) so backpressure halves submission rate uniformly.
            double u = sleepRng.nextDouble();
            // Guard against u==0 producing infinity.
            double effectiveMean = meanInterArrivalMs * throttleFactor.get();
            long sleepMs = (long) (-effectiveMean * Math.log(Math.max(u, 1e-9)));
            try {
                Thread.sleep(Math.max(0, sleepMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Plan §9.1 — backpressure decision. Latches throttle if EITHER
     * trigger fires; releases when both clear, with a small dead-band
     * (must drop below 1.5× baseline before un-throttling so we don't
     * oscillate around exactly 2×).
     *
     * <p>Public for {@code LoadRunnerTest}.
     */
    static void evaluateBackpressure(LoadStats.Snapshot snap,
                                     AtomicLong baselineP99Nanos,
                                     AtomicReference<Double> throttleFactor) {
        // First non-empty window seeds the baseline.
        if (snap.windowSamples() > 0 && baselineP99Nanos.get() == 0) {
            baselineP99Nanos.set(snap.p99Nanos());
        }
        long baseline = baselineP99Nanos.get();
        boolean spike503 = snap.windowServer503() > 0;
        boolean spikeP99 = baseline > 0 && snap.p99Nanos() > 2L * baseline;
        boolean cleared  = !spike503
                && (baseline == 0 || snap.p99Nanos() < (long) (1.5 * baseline));

        Double current = throttleFactor.get();
        if (spike503 || spikeP99) {
            if (current == 1.0) {
                LOG.warn("backpressure ENGAGED — halving submit rate "
                        + "(p99={}ms baseline={}ms 503s={})",
                        snap.p99Nanos() / 1_000_000,
                        baseline / 1_000_000, snap.windowServer503());
                throttleFactor.set(2.0);
            }
        } else if (cleared && current != 1.0) {
            LOG.info("backpressure RELEASED — restoring nominal rate "
                    + "(p99={}ms baseline={}ms)",
                    snap.p99Nanos() / 1_000_000, baseline / 1_000_000);
            throttleFactor.set(1.0);
        }
    }

    /** Test seam — exposes the username/symbol routing for assertion. */
    public List<String> usernames() {
        return usernames.stream().collect(Collectors.toUnmodifiableList());
    }
}
