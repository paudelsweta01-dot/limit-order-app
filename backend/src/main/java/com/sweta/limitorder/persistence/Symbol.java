package com.sweta.limitorder.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "symbols")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Symbol {

    @Id
    @Column(name = "symbol", nullable = false, updatable = false)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "ref_price", nullable = false)
    private BigDecimal refPrice;
}
