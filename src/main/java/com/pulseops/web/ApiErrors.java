package com.pulseops.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiErrors {
    public record Error(Instant timestamp, int status, String message, String path, List<String> details) {}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> api(ApiException e, HttpServletRequest r) { return error(e.status(), e.getMessage(), r, List.of()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        return error(400, "Validation failed", r, e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
    }
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Error> badRequest(Exception e, HttpServletRequest r) { return error(400, "Invalid request", r, List.of()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> conflict(DataIntegrityViolationException e, HttpServletRequest r) {
        return error(409, "Data conflicts with an existing record", r, List.of());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception e, HttpServletRequest r) {
        LoggerFactory.getLogger(ApiErrors.class).error("Unexpected request failure", e);
        return error(500, "Unexpected server error", r, List.of());
    }
    private ResponseEntity<Error> error(int status, String message, HttpServletRequest r, List<String> details) {
        return ResponseEntity.status(status).body(new Error(Instant.now(), status, message, r.getRequestURI(), details));
    }
}
