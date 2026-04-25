package com.sweta.limitorder.outbox;

import com.sweta.limitorder.ws.WsBroker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Receives outbox row ids from the {@link OutboxListener}, fetches the
 * corresponding row, and forwards the payload to {@link WsBroker} on the
 * channel encoded in the row.
 *
 * <p>If the row no longer exists when we look it up — typically because
 * the {@link OutboxJanitor} ran between the trigger firing and the
 * notification reaching us — we log and skip. Late delivery of a payload
 * whose state is already in clients' snapshots is harmless; missing the
 * row entirely just means the same.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxFanout {

    private final OutboxRepository outbox;
    private final WsBroker broker;

    public void handle(long id) {
        Optional<OutboxRow> rowOpt = outbox.findById(id);
        if (rowOpt.isEmpty()) {
            log.debug("event=OUTBOX_ROW_MISSING id={}", id);
            return;
        }
        OutboxRow row = rowOpt.get();
        broker.publish(row.channel(), row.payload(), row.id());
        log.debug("event=OUTBOX_FORWARDED id={} channel={}", row.id(), row.channel());
    }
}
