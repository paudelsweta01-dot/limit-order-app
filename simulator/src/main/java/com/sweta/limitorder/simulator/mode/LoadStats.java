package com.sweta.limitorder.simulator.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters + a rolling latency window for the load mode's
 * 5-second progress logs (plan §5.5). Cumulative counters survive the
 * whole run; the latency window resets on each
 * {@link #snapshotAndReset()} so percentiles describe the *recent*
 * distribution, not a smeared lifetime average.
 */
public final class LoadStats {

    private final LongAdder submitted = new LongAdder();
    private final LongAdder accepted  = new LongAdder();
    private final LongAdder rejected  = new LongAdder();
    /** 503s in the *current* window — reset on snapshot. Drives backpressure
     *  evaluation (plan §9.1). Does NOT shadow {@link #rejected}; a 503 is
     *  counted in both. */
    private final LongAdder windowServer503 = new LongAdder();

    /** Current-window latency samples in nanoseconds. */
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    public void recordSubmitted()   { submitted.increment(); }
    public void recordAccepted()    { accepted.increment(); }
    public void recordRejected()    { rejected.increment(); }
    public void recordServer503()   { windowServer503.increment(); }
    public void recordLatencyNanos(long nanos) { latencies.add(nanos); }

    public long submitted() { return submitted.sum(); }
    public long accepted()  { return accepted.sum(); }
    public long rejected()  { return rejected.sum(); }

    /** Snapshot of current latency window + reset for the next interval. */
    public Snapshot snapshotAndReset() {
        long[] sortedNanos;
        synchronized (latencies) {
            sortedNanos = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            latencies.clear();
        }
        long server503Window = windowServer503.sumThenReset();
        if (sortedNanos.length == 0) {
            return new Snapshot(submitted(), accepted(), rejected(),
                    0, 0, 0, 0, server503Window);
        }
        return new Snapshot(submitted(), accepted(), rejected(),
                sortedNanos.length,
                percentile(sortedNanos, 0.50),
                percentile(sortedNanos, 0.95),
                percentile(sortedNanos, 0.99),
                server503Window);
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    public record Snapshot(
            long submitted, long accepted, long rejected,
            int windowSamples,
            long p50Nanos, long p95Nanos, long p99Nanos,
            long windowServer503) {

        public String formatLine() {
            return "load: submitted=" + submitted
                    + " accepted=" + accepted
                    + " rejected=" + rejected
                    + " | window=" + windowSamples
                    + " p50=" + (p50Nanos / 1_000_000) + "ms"
                    + " p95=" + (p95Nanos / 1_000_000) + "ms"
                    + " p99=" + (p99Nanos / 1_000_000) + "ms"
                    + (windowServer503 > 0 ? " 503=" + windowServer503 : "");
        }
    }
}
