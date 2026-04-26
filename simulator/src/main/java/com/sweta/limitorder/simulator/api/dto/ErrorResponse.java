package com.sweta.limitorder.simulator.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Mirrors {@code com.sweta.limitorder.api.error.ErrorResponse} —
 * the §4.11 envelope. Deserialised from any 4xx/5xx response body and
 * carried inside {@code LobApiException}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldViolation> details) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldViolation(String field, String message) {}
}
