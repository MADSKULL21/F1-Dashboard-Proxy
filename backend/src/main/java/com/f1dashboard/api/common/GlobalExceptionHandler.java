package com.f1dashboard.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Central exception handling (PRD 9). Every failure leaves the application as an
 * {@link ApiError}, so the frontend only ever has to understand one error shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    /** Thrown by Spring MVC when no handler and no static resource matches the path. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResourceFound(HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint matches this path", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request) {
        log.debug("Rejected invalid request to {}", request.getRequestURI(), ex);
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request parameters are invalid", request);
    }

    /**
     * Last resort. The exception is logged in full but the client is told
     * nothing specific — an internal message could name a host, a table or a
     * credential.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception serving {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                             HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, request.getRequestURI()));
    }
}
