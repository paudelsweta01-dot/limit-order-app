package com.sweta.limitorder.book;

/**
 * Sum of remaining open BUY quantity (demand) and SELL quantity (supply)
 * for a single symbol — drives the §6.2 Market Overview row.
 */
public record BookTotals(long demand, long supply) {
}
