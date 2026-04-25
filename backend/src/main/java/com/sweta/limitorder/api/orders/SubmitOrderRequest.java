package com.sweta.limitorder.api.orders;

import com.sweta.limitorder.persistence.OrderSide;
import com.sweta.limitorder.persistence.OrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Wire shape for {@code POST /api/orders}.
 *
 * <p>Top-level shape is enforced by Bean Validation; the cross-field rule
 * "price required iff LIMIT" is enforced by the matching layer's
 * {@link com.sweta.limitorder.matching.SubmitOrderCommand} constructor —
 * the {@code GlobalExceptionHandler} maps the resulting
 * {@link IllegalArgumentException} to a 400 with code {@code VALIDATION_FAILED}.
 */
public record SubmitOrderRequest(
        @NotBlank String clientOrderId,
        @NotBlank String symbol,
        @NotNull  OrderSide side,
        @NotNull  OrderType type,
        BigDecimal price,
        @Positive long quantity
) {
}
