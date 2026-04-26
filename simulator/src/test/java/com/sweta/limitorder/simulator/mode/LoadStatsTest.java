package com.sweta.limitorder.simulator.mode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoadStatsTest {

    @Test
    void countersAccumulateAcrossSnapshots() {
        var stats = new LoadStats();
        for (int i = 0; i < 5; i++) {
            stats.recordSubmitted();
            stats.recordAccepted();
            stats.recordLatencyNanos(1_000_000L);
        }
        var snap = stats.snapshotAndReset();
        assertThat(snap.submitted()).isEqualTo(5);
        assertThat(snap.accepted()).isEqualTo(5);
        assertThat(snap.windowSamples()).isEqualTo(5);

        // Cumulative counters survive reset; latency window is fresh.
        stats.recordSubmitted();
        var snap2 = stats.snapshotAndReset();
        assertThat(snap2.submitted()).isEqualTo(6);
        assertThat(snap2.windowSamples()).isZero();
    }

    @Test
    void percentilesOrderedAndMonotonic() {
        var stats = new LoadStats();
        for (long ms = 1; ms <= 100; ms++) {
            stats.recordLatencyNanos(ms * 1_000_000L);
        }
        var snap = stats.snapshotAndReset();
        // Sorted samples are 1..100ms; p50 ≈ 50ms, p95 ≈ 95ms, p99 ≈ 99ms.
        assertThat(snap.p50Nanos()).isBetween(48_000_000L, 52_000_000L);
        assertThat(snap.p95Nanos()).isBetween(93_000_000L, 96_000_000L);
        assertThat(snap.p99Nanos()).isBetween(98_000_000L, 100_000_000L);
        assertThat(snap.p50Nanos()).isLessThan(snap.p95Nanos());
        assertThat(snap.p95Nanos()).isLessThanOrEqualTo(snap.p99Nanos());
    }

    @Test
    void formatLineMentionsKeyFields() {
        var stats = new LoadStats();
        stats.recordSubmitted(); stats.recordAccepted();
        stats.recordLatencyNanos(5_000_000L);
        var snap = stats.snapshotAndReset();
        assertThat(snap.formatLine())
                .contains("submitted=1")
                .contains("accepted=1")
                .contains("p50=")
                .contains("p99=");
    }
}
