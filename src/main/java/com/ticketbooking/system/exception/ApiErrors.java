package com.ticketbooking.system.exception;

import com.ticketbooking.system.logging.RequestIds;
import com.ticketbooking.system.observability.ObservabilityMetrics;
import com.ticketbooking.system.observability.ReservationOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.Instant;

record ApiError(Instant timestamp, String request_id, int status, String code, String message) {
}

@RestControllerAdvice
public class ApiErrors {
    private final ObservabilityMetrics metrics;

    public ApiErrors(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException exception) {
        return error(exception.status, exception.code, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        recordInvalidReservation(request);
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception exception, HttpServletRequest request) {
        recordInvalidReservation(request);
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        recordInvalidReservation(request);
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        metrics.recordDatabaseError();
        return error(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "The request conflicts with existing data");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> dataAccess(DataAccessException exception) {
        metrics.recordDatabaseError();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "Database error");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), RequestIds.get(), status.value(), code, message));
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"timestamp\":\"" + Instant.now() + "\",\"request_id\":" + json(RequestIds.get())
                + ",\"status\":" + status + ",\"code\":" + json(code) + ",\"message\":" + json(message) + "}";
        response.getWriter().write(body);
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void recordInvalidReservation(HttpServletRequest request) {
        if ("POST".equals(request.getMethod()) && request.getRequestURI().matches("/shows/[^/]+/reserve")) {
            metrics.recordReservationAttempt();
            metrics.recordReservationOutcome(ReservationOutcome.INVALID_REQUEST, java.time.Duration.ZERO);
        }
    }
}
