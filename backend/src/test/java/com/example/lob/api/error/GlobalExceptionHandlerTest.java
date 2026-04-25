package com.example.lob.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler advice = new GlobalExceptionHandler();

    @Test
    void validationReturns400WithFieldDetails() {
        MethodParameter param = mock(MethodParameter.class);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "submitOrderRequest");
        binding.addError(new FieldError("submitOrderRequest", "qty", "must be at least 1"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, binding);

        ResponseEntity<ErrorResponse> response = advice.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.message()).isEqualTo("qty must be at least 1");
        assertThat(body.details()).hasSize(1);
        assertThat(body.details().get(0).field()).isEqualTo("qty");
        assertThat(body.details().get(0).message()).isEqualTo("must be at least 1");
    }

    @Test
    void dataIntegrityViolationReturns409Conflict() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate key value violates unique constraint");

        ResponseEntity<ErrorResponse> response = advice.handleConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("request conflicts with existing state");
        assertThat(response.getBody().details()).isNull();
    }

    @Test
    void illegalStateReturns409WithCallerMessage() {
        IllegalStateException ex = new IllegalStateException("order already filled");

        ResponseEntity<ErrorResponse> response = advice.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_STATE");
        assertThat(response.getBody().message()).isEqualTo("order already filled");
    }

    @Test
    void accessDeniedReturns403() {
        AccessDeniedException ex = new AccessDeniedException("nope");

        ResponseEntity<ErrorResponse> response = advice.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unhandledReturns500WithoutLeakingStackTrace() {
        ResponseEntity<ErrorResponse> response =
                advice.handleUnexpected(new RuntimeException("ka-boom inside the engine"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL");
        assertThat(response.getBody().message()).isEqualTo("unexpected error");
        assertThat(response.getBody().message()).doesNotContain("ka-boom");
    }
}
