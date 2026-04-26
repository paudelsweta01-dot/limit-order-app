package com.sweta.limitorder.simulator.mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.api.dto.OrderType;

class ScenarioCsvTest {

    @Test
    void parsesFullSeedCsvFromTheRequirementsFixture() throws IOException {
        var rows = ScenarioCsv.parse(Path.of("../docs/requirnments/seed.csv"));
        assertThat(rows).hasSize(10);
        var first = rows.get(0);
        assertThat(first.clientOrderId()).isEqualTo("c001");
        assertThat(first.userId()).isEqualTo("u1");
        assertThat(first.symbol()).isEqualTo("AAPL");
        assertThat(first.side()).isEqualTo(OrderSide.SELL);
        assertThat(first.type()).isEqualTo(OrderType.LIMIT);
        assertThat(first.price()).isEqualByComparingTo(new BigDecimal("181.00"));
        assertThat(first.quantity()).isEqualTo(100L);

        // c009 is a MARKET order — empty price column, parsed as null.
        var marketRow = rows.stream().filter(r -> r.clientOrderId().equals("c009")).findFirst().orElseThrow();
        assertThat(marketRow.type()).isEqualTo(OrderType.MARKET);
        assertThat(marketRow.price()).isNull();
    }

    @Test
    void skipsCommentLinesAndBlankLines(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("with-comments.csv");
        Files.writeString(csv, """
                # leading comment
                clientOrderId,userId,symbol,side,type,price,quantity

                c001,u1,AAPL,BUY,LIMIT,180.00,50
                # trailing comment
                """);
        var rows = ScenarioCsv.parse(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).clientOrderId()).isEqualTo("c001");
    }

    @Test
    void rejectsMalformedRowWithLineNumberInMessage(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("bad.csv");
        Files.writeString(csv, """
                clientOrderId,userId,symbol,side,type,price,quantity
                c001,u1,AAPL
                """);
        assertThatThrownBy(() -> ScenarioCsv.parse(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("expected 7 columns, got 3");
    }
}
