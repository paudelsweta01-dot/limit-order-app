package com.sweta.limitorder.simulator.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MyFillResponse(
        UUID tradeId,
        String symbol,
        OrderSide side,
        BigDecimal price,
        long quantity,
        Instant executedAt,
        String counterparty
) {}
