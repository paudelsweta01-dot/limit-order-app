package com.sweta.limitorder.simulator.api.dto;

import java.math.BigDecimal;

public record BookLevel(BigDecimal price, long qty, int userCount) {}
