package com.sweta.limitorder.outbox;

import java.time.Instant;

public record OutboxRow(long id, String channel, String payload, Instant createdAt) {
}
