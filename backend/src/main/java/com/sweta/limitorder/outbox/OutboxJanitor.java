package com.sweta.limitorder.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Trims {@code market_event_outbox} rows older than
 * {@code app.outbox.retention-minutes} (default 5 minutes — see
 * {@code application.yml}).
 *
 * <p>Outbox rows exist only to survive the gap between commit and
 * fan-out across nodes. Any row older than the retention window has
 * either been delivered everywhere or is unrecoverable; either way it's
 * just bloat. Architecture §4.7: "outbox janitor exists only to keep the
 * table small; correctness doesn't depend on it."
 */
@Component
@Slf4j
public class OutboxJanitor {

    private final OutboxRepository outbox;
    private final Duration retention;

    public OutboxJanitor(OutboxRepository outbox,
                         @Value("${app.outbox.retention-minutes}") long retentionMinutes) {
        this.outbox = outbox;
        this.retention = Duration.ofMinutes(retentionMinutes);
    }

    @Scheduled(fixedDelayString = "${app.outbox.janitor-interval-ms:60000}",
               initialDelayString = "${app.outbox.janitor-interval-ms:60000}")
    public void purge() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = outbox.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("event=OUTBOX_PURGED rowsDeleted={} cutoffBefore={}", deleted, cutoff);
        }
    }
}
