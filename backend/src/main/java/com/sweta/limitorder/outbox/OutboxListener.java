package com.sweta.limitorder.outbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Consumes Postgres {@code LISTEN market_event} notifications and forwards
 * each to {@link OutboxFanout}.
 *
 * <p><b>Why a separate connection (not Hikari):</b> the LISTEN registration
 * lives on a single physical connection; if Hikari rotates / closes /
 * recycles the connection, the registration is gone and we silently miss
 * NOTIFYs. This bean opens its own {@link DriverManager} connection at
 * startup, holds it for the JVM's lifetime, and closes it at
 * {@link PreDestroy}. The blocked {@code getNotifications} call returns
 * with a {@link SQLException} the moment the connection is closed, which
 * unblocks the daemon thread for a clean shutdown (architecture §4.7).
 *
 * <p>Connection failures (Postgres restart, network blip) terminate the
 * thread; the actuator health indicator (added in Phase 9) flips to DOWN.
 * Reconnect logic is deliberately out of scope.
 */
@Component
@Slf4j
public class OutboxListener {

    private static final String CHANNEL = "market_event";
    private static final long POLL_TIMEOUT_MS = 1000L;
    private static final long SHUTDOWN_JOIN_MS = 5000L;

    private final JdbcConnectionDetails connectionDetails;
    private final OutboxFanout fanout;

    private volatile boolean stopRequested = false;
    private Connection connection;
    private Thread listenerThread;

    public OutboxListener(JdbcConnectionDetails connectionDetails, OutboxFanout fanout) {
        this.connectionDetails = connectionDetails;
        this.fanout = fanout;
    }

    @PostConstruct
    void start() throws SQLException {
        // JdbcConnectionDetails reflects whatever is actually wired up
        // (Hikari config in prod, the dynamic Testcontainers URL in tests).
        // We deliberately bypass HikariDataSource here — see class javadoc.
        this.connection = DriverManager.getConnection(
                connectionDetails.getJdbcUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword());
        this.connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("LISTEN " + CHANNEL);
        }
        this.listenerThread = new Thread(this::loop, "outbox-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
        log.info("event=OUTBOX_LISTENER_STARTED channel={}", CHANNEL);
    }

    /**
     * Replays any outbox rows that committed between the trigger firing and
     * us being ready to consume notifications. The cursor is the highest id
     * we know about at startup; we forward everything strictly newer that
     * exists at this moment. Idempotent on the WS side because the broker
     * publishes by row id and clients deduplicate against snapshots.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        // Phase 7 leaves this empty intentionally — the WS broker doesn't
        // exist yet. Phase 8 fills in real recovery; for now any rows that
        // race startup are simply purged by the janitor.
    }

    private void loop() {
        PGConnection pg;
        try {
            pg = connection.unwrap(PGConnection.class);
        } catch (SQLException e) {
            log.error("failed to unwrap PgConnection — listener will not start", e);
            return;
        }

        while (!stopRequested) {
            try {
                PGNotification[] notifications = pg.getNotifications(Math.toIntExact(POLL_TIMEOUT_MS));
                if (notifications == null) continue;
                for (PGNotification n : notifications) {
                    long id;
                    try {
                        id = Long.parseLong(n.getParameter());
                    } catch (NumberFormatException e) {
                        log.warn("event=OUTBOX_NOTIFICATION_BAD_PARAM parameter={}", n.getParameter());
                        continue;
                    }
                    try {
                        fanout.handle(id);
                    } catch (Exception e) {
                        // Don't let one bad row kill the listener thread.
                        log.error("event=OUTBOX_FANOUT_ERROR id={}", id, e);
                    }
                }
            } catch (SQLException e) {
                if (stopRequested) {
                    break;  // expected on @PreDestroy connection close
                }
                log.error("event=OUTBOX_LISTENER_DB_ERROR — listener thread terminating", e);
                break;
            }
        }
        log.info("event=OUTBOX_LISTENER_STOPPED");
    }

    @PreDestroy
    void stop() {
        stopRequested = true;
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("error closing outbox listener connection", e);
        }
        if (listenerThread != null) {
            try {
                listenerThread.join(SHUTDOWN_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return whether the listener thread is alive. Used by tests + Phase 9 health. */
    public boolean isRunning() {
        return listenerThread != null && listenerThread.isAlive();
    }
}
