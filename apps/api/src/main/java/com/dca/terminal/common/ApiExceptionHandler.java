package com.dca.terminal.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (first, ignored) -> first));
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request);
        detail.setProperty("fields", fields);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraint(ConstraintViolationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail responseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return problem(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status, "REQUEST_REJECTED",
                exception.getReason() == null ? "Request rejected" : exception.getReason(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "Request conflicts with existing data", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String message, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(code);
        detail.setType(URI.create("https://dca-terminal.invalid/problems/" + code.toLowerCase(java.util.Locale.ROOT)));
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", code);
        detail.setProperty("timestamp", Instant.now());
        detail.setProperty("path", request.getRequestURI());
        return detail;
    }
}
