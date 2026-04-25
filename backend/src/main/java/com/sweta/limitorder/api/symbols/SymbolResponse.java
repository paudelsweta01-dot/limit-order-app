package com.sweta.limitorder.api.symbols;

import java.math.BigDecimal;

public record SymbolResponse(String symbol, String name, BigDecimal refPrice) {
}
