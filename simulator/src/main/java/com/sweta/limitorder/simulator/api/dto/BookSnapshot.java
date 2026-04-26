package com.sweta.limitorder.simulator.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookSnapshot(
        String symbol,
        List<BookLevel> bids,
        List<BookLevel> asks,
        BigDecimal last,
        long cursor
) {}
