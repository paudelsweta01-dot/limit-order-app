package com.sweta.limitorder.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldViolation> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse of(String code, String message, List<FieldViolation> details) {
        return new ErrorResponse(code, message, details);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldViolation(String field, String message) {}
}
