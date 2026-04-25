package com.sweta.limitorder.outbox;

import com.sweta.limitorder.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9.3 — {@link OutboxListenerHealthIndicator} reports UP when the
 * listener thread is alive, DOWN when it isn't, and the indicator is
 * surfaced under /actuator/health's {@code components}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class OutboxListenerHealthIndicatorTest {

    @Autowired private TestRestTemplate rest;

    // ---------- direct unit tests on the indicator ----------

    @Test
    void healthIsUpWhenListenerThreadIsAlive() {
        OutboxListener listener = mock(OutboxListener.class);
        when(listener.isRunning()).thenReturn(true);

        Health health = new OutboxListenerHealthIndicator(listener).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("thread", "alive");
    }

    @Test
    void healthIsDownWhenListenerThreadIsStopped() {
        OutboxListener listener = mock(OutboxListener.class);
        when(listener.isRunning()).thenReturn(false);

        Health health = new OutboxListenerHealthIndicator(listener).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("thread", "stopped")
                .containsKey("reason");
    }

    // ---------- /actuator/health surfaces the component ----------

    @Test
    @SuppressWarnings("unchecked")
    void actuatorHealthIncludesOutboxListenerComponent() {
        Map<String, Object> body = rest.getForObject("/actuator/health", Map.class);

        assertThat(body).containsEntry("status", "UP");
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components)
                .as("outbox listener health must be visible under components")
                .containsKey("outboxListener");

        Map<String, Object> outbox = (Map<String, Object>) components.get("outboxListener");
        assertThat(outbox).containsEntry("status", "UP");
        Map<String, Object> details = (Map<String, Object>) outbox.get("details");
        assertThat(details).containsEntry("thread", "alive");
    }
}
