package com.sweta.limitorder.outbox;

import com.sweta.limitorder.TestcontainersConfig;
import com.sweta.limitorder.ws.WsBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 7.2 + 7.3 — end-to-end through the V1 trigger:
 * INSERT into market_event_outbox → Postgres pg_notify → OutboxListener
 * unblocks getNotifications → OutboxFanout fetches the row → WsBroker.publish.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class OutboxListenerIntegrationTest {

    @MockBean
    private WsBroker broker;

    @Autowired
    private OutboxListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE market_event_outbox RESTART IDENTITY");
    }

    @Test
    void listenerThreadIsRunningAfterContextStartup() {
        assertThat(listener.isRunning()).isTrue();
    }

    @Test
    void notifyFromTriggerForwardsRowToBroker() {
        jdbc.update(
                "INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "book:AAPL", "{\"hello\":\"world\"}");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(broker).publish(
                        eq("book:AAPL"),
                        eq("{\"hello\": \"world\"}"),  // jsonb roundtrip canonicalises spacing
                        anyLong()));
    }

    @Test
    void cursorMonotonicallyIncreasesAcrossEvents() {
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "trades:AAPL", "{\"n\":1}");
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "trades:AAPL", "{\"n\":2}");
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "trades:AAPL", "{\"n\":3}");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
            verify(broker, times(3))
                    .publish(eq("trades:AAPL"), anyString(), cursors.capture());
            List<Long> all = cursors.getAllValues();
            assertThat(all).isSorted();
        });
    }

    @Test
    void differentChannelsAreRoutedIndependently() {
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "book:AAPL", "{}");
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "trades:MSFT", "{}");
        jdbc.update("INSERT INTO market_event_outbox (channel, payload) VALUES (?, ?::jsonb)",
                "orders:00000000-0000-0000-0000-000000000001", "{}");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(broker).publish(eq("book:AAPL"),    anyString(), anyLong());
            verify(broker).publish(eq("trades:MSFT"),  anyString(), anyLong());
            verify(broker).publish(
                    eq("orders:00000000-0000-0000-0000-000000000001"),
                    anyString(), anyLong());
        });
    }
}
