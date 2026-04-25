package com.sweta.limitorder.outbox;

import com.sweta.limitorder.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7.4 — janitor purges rows older than the configured retention,
 * fresh rows are preserved.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class OutboxJanitorIntegrationTest {

    @Autowired private OutboxJanitor janitor;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE market_event_outbox RESTART IDENTITY");
    }

    @Test
    void purgeRemovesRowsOlderThanRetentionAndKeepsFreshOnes() {
        // Insert one row well past retention (default 5 minutes — see application.yml).
        jdbc.update(
                "INSERT INTO market_event_outbox (channel, payload, created_at) " +
                        "VALUES (?, ?::jsonb, ?)",
                "stale", "{}",
                OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        // Insert one fresh row (created_at defaults to now()).
        jdbc.update(
                "INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "fresh", "{}");

        janitor.purge();

        List<String> remaining = jdbc.queryForList(
                "SELECT channel FROM market_event_outbox ORDER BY id", String.class);
        assertThat(remaining).containsExactly("fresh");
    }

    @Test
    void purgeOnEmptyTableIsANoOp() {
        janitor.purge();   // no exceptions, no rows touched
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM market_event_outbox", Long.class);
        assertThat(count).isZero();
    }
}
