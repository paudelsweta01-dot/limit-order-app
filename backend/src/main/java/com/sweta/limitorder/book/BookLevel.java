package com.sweta.limitorder.book;

import java.math.BigDecimal;

/**
 * One aggregated price level in the order book.
 *
 * <p>{@code qty} is the sum of remaining (unfilled) quantity across every
 * order at this price; {@code userCount} is the distinct number of users
 * resting at the level. Architecture §6.3 (Symbol Detail wireframe) shows
 * both columns to the user.
 */
public record BookLevel(BigDecimal price, long qty, int userCount) {
}
