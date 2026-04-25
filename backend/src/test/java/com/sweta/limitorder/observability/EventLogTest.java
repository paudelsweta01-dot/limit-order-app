package com.sweta.limitorder.observability;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.marker.LogstashMarker;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9.1 — verifies {@link EventLog#event} produces a marker chain whose
 * fields will land as first-class JSON properties on the wire (so
 * {@code grep '"event":"ORDER_ACCEPTED"'} on captured logs actually works).
 */
class EventLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void eventBuildsMarkerWithEventNameAndAllKvs() throws Exception {
        LogstashMarker marker = EventLog.event(EventLog.ORDER_ACCEPTED,
                "orderId", "abc-123",
                "symbol",  "AAPL",
                "qty",     100L);

        Map<String, Object> fields = renderMarkerToJson(marker);

        assertThat(fields)
                .containsEntry("event",   "ORDER_ACCEPTED")
                .containsEntry("orderId", "abc-123")
                .containsEntry("symbol",  "AAPL")
                .containsEntry("qty",     100);   // Jackson reads small longs as Integer
    }

    @Test
    void eventWithNoExtraFieldsStillCarriesEventName() throws Exception {
        LogstashMarker marker = EventLog.event(EventLog.ORDER_CANCELLED);
        assertThat(renderMarkerToJson(marker)).containsEntry("event", "ORDER_CANCELLED");
    }

    @Test
    void eventRejectsOddNumberOfKvArguments() {
        assertThatThrownBy(() ->
                EventLog.event(EventLog.ORDER_FILLED, "orderId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(key, value) pairs");
    }

    @Test
    void eventConstantsCoverThePlanContract() {
        // The five event names called out by Phase 9.1 / architecture §4.10.
        assertThat(EventLog.ORDER_ACCEPTED).isEqualTo("ORDER_ACCEPTED");
        assertThat(EventLog.ORDER_REJECTED).isEqualTo("ORDER_REJECTED");
        assertThat(EventLog.ORDER_FILLED).isEqualTo("ORDER_FILLED");
        assertThat(EventLog.ORDER_CANCELLED).isEqualTo("ORDER_CANCELLED");
        assertThat(EventLog.TRADE_EXECUTED).isEqualTo("TRADE_EXECUTED");
    }

    /**
     * Render the marker chain as JSON exactly the way the {@code LogstashEncoder}
     * does it on the wire, then parse back to a Map for assertions.
     * Goes through {@link LogstashMarker#writeTo(JsonGenerator)} — the public
     * path, since {@code getFieldValue()} on individual markers is protected.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> renderMarkerToJson(LogstashMarker marker) throws IOException {
        StringWriter writer = new StringWriter();
        try (JsonGenerator gen = JSON.getFactory().createGenerator(writer)) {
            gen.writeStartObject();
            marker.writeTo(gen);
            Iterator<Marker> children = marker.iterator();
            while (children.hasNext()) {
                Marker child = children.next();
                if (child instanceof LogstashMarker lm) {
                    lm.writeTo(gen);
                }
            }
            gen.writeEndObject();
        }
        return JSON.readValue(writer.toString(), Map.class);
    }
}
