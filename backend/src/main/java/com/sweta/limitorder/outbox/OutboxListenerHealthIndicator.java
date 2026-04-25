package com.sweta.limitorder.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the {@link OutboxListener} thread state into {@code /actuator/health}.
 *
 * <p>If the listener thread dies (DB connection drop, an unhandled exception
 * in the loop, or anything else that breaks out of the polling loop), the
 * pipeline is silently broken — order-state changes still commit, but no
 * NOTIFY is consumed and WebSocket clients on this node never see deltas.
 * The health indicator is the operator-facing signal that the node has
 * effectively become an island: serves HTTP fine, but its real-time stream
 * is dark. Architecture §4.10.
 */
@Component
@RequiredArgsConstructor
public class OutboxListenerHealthIndicator implements HealthIndicator {

    private final OutboxListener listener;

    @Override
    public Health health() {
        if (listener.isRunning()) {
            return Health.up()
                    .withDetail("thread", "alive")
                    .build();
        }
        return Health.down()
                .withDetail("thread", "stopped")
                .withDetail("reason",
                        "outbox listener thread is not running; commits won't fan out to WebSockets on this node")
                .build();
    }
}
