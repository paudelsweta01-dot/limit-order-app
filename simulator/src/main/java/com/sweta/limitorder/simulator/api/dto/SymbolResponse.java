package com.sweta.limitorder.simulator.api.dto;

import java.math.BigDecimal;

public record SymbolResponse(String symbol, String name, BigDecimal refPrice) {}
