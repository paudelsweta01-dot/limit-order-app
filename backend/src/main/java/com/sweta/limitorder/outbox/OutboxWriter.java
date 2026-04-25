package com.sweta.limitorder.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-transaction writer for {@code market_event_outbox} rows.
 *
 * <p>Every row inserted here triggers a Postgres {@code pg_notify} on the
 * {@code market_event} channel after commit (see V1 trigger). The architecture
 * makes this the single, transactionally-consistent fan-out hop:
 *
 * <ol>
 *   <li>Match transaction inserts state-changing rows AND outbox rows.
 *   <li>Commit fires NOTIFY exactly once per outbox row.
 *   <li>Every backend node holds a dedicated LISTEN connection and pushes
 *       the corresponding payload to its connected WebSocket clients.
 * </ol>
 *
 * <p>The {@code @Transactional} surrounding caller is mandatory — committing
 * outbox rows without committing the state change they describe would create
 * phantom events. We assert it explicitly to catch misuse early.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    /**
     * Insert one outbox event. Must be called from within an active transaction.
     *
     * @param channel       routing key, e.g. "book:AAPL", "trades:AAPL", "orders:{userId}"
     * @param jsonPayload   JSON body to be delivered as-is to the WS client
     */
    public void emit(String channel, String jsonPayload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter.emit must be called inside an active transaction; " +
                            "outbox rows must commit atomically with the state change they describe.");
        }
        jdbc.update(
                "INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                channel, jsonPayload);
        meters.counter("outbox_published_total").increment();
    }
}
