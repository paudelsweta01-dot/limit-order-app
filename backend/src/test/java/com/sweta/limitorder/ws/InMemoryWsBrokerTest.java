package com.sweta.limitorder.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 8.2 — concurrent register / unregister / publish on
 * {@link InMemoryWsBroker} must not throw or lose subscribers.
 */
class InMemoryWsBrokerTest {

    @Test
    void concurrentSubscribeAndUnsubscribeFromManyThreadsHoldsTogether() throws Exception {
        InMemoryWsBroker broker = new InMemoryWsBroker();

        int threads = 32;
        int iterations = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                String channel = "book:AAPL";
                for (int i = 0; i < iterations; i++) {
                    WebSocketSession session = mock(WebSocketSession.class);
                    when(session.getId()).thenReturn(UUID.randomUUID().toString());
                    when(session.isOpen()).thenReturn(false); // skip actual sends
                    broker.subscribe(channel, session);
                    broker.unsubscribeAll(session);
                }
                return null;
            });
        }

        try {
            List<Future<Void>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Void> r : results) r.get();
        } finally {
            pool.shutdown();
        }

        // After every thread has unsubscribed everything it added, no leak.
        assertThat(broker.subscriberCount("book:AAPL")).isZero();
    }

    @Test
    void publishToNonExistentChannelIsNoOp() {
        InMemoryWsBroker broker = new InMemoryWsBroker();
        broker.publish("nobody-listens-here", "{}", 42L); // doesn't throw
        assertThat(broker.subscriberCount("nobody-listens-here")).isZero();
    }

    @Test
    void closedSessionsAreSkippedDuringPublish() {
        InMemoryWsBroker broker = new InMemoryWsBroker();
        WebSocketSession closed = mock(WebSocketSession.class);
        when(closed.isOpen()).thenReturn(false);
        broker.subscribe("book:AAPL", closed);

        broker.publish("book:AAPL", "{\"k\":1}", 1L); // no exception
        assertThat(broker.subscriberCount("book:AAPL")).isOne(); // membership preserved
    }
}
