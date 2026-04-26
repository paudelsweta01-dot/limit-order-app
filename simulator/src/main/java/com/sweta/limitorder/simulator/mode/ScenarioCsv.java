package com.sweta.limitorder.simulator.mode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sweta.limitorder.simulator.api.dto.OrderSide;
import com.sweta.limitorder.simulator.api.dto.OrderType;

/**
 * Parses a CSV in the §5.3 schema:
 * {@code clientOrderId,userId,symbol,side,type,price,quantity}.
 *
 * <p>The {@code price} column is empty for MARKET orders (per the
 * §5.3 fixture's {@code c009} row). We carry it as null on the
 * record so {@link com.sweta.limitorder.simulator.api.dto.SubmitOrderRequest}
 * can pass it through verbatim and let the backend's bean-validation
 * cross-field rule fire.
 */
public final class ScenarioCsv {

    public record Row(
            String clientOrderId,
            String userId,        // CSV calls it userId; values are usernames (u1..u4) per the fixture
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal price,     // null for MARKET
            long quantity
    ) {}

    private ScenarioCsv() {}

    public static List<Row> parse(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        boolean first = true;
        int lineNo = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (first) {
                first = false;
                if (line.toLowerCase().startsWith("clientorderid")) continue;
            }
            rows.add(parseRow(line, lineNo));
        }
        return rows;
    }

    private static Row parseRow(String line, int lineNo) {
        String[] cols = line.split(",", -1);
        if (cols.length != 7) {
            throw new IllegalArgumentException("line " + lineNo
                    + ": expected 7 columns, got " + cols.length + ": " + line);
        }
        try {
            String priceCol = cols[5].strip();
            return new Row(
                    cols[0].strip(),
                    cols[1].strip(),
                    cols[2].strip(),
                    OrderSide.valueOf(cols[3].strip()),
                    OrderType.valueOf(cols[4].strip()),
                    priceCol.isEmpty() ? null : new BigDecimal(priceCol),
                    Long.parseLong(cols[6].strip()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("line " + lineNo
                    + ": failed to parse '" + line + "' — " + e.getMessage(), e);
        }
    }
}
