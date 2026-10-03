package com.ticketbooking.system.controller;

import com.ticketbooking.system.dto.Contracts.*;

import com.ticketbooking.system.exception.DomainException;
import com.ticketbooking.system.logging.RequestIds;
import com.ticketbooking.system.observability.ObservabilityMetrics;
import com.ticketbooking.system.observability.ReservationOutcome;
import com.ticketbooking.system.service.ReservationService;
import com.ticketbooking.system.service.ReservationService.ReservationResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
public class ReservationController {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService service;
    private final ObservabilityMetrics metrics;

    public ReservationController(ReservationService s, ObservabilityMetrics metrics) {
        service = s;
        this.metrics = metrics;
    }

    @PostMapping("/shows/{id}/reserve")
    @Operation(summary = "Reserve seats", description = "Requires USER. The JWT subject is the owner; multi-seat reservations are all-or-nothing. Idempotency is scoped to show + user + key.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReservationView> reserve(@PathVariable UUID id, @Valid @RequestBody Reserve r,
            Authentication a) {
        metrics.recordReservationAttempt();
        long start = System.nanoTime();
        try {
            ReservationResult result = service.reserveWithOutcome(id, a.getName(), r);
            recordReservation(id, a.getName(), result.view().reservation_id(), result.outcome(), start);
            return ResponseEntity.status(HttpStatus.CREATED).body(result.view());
        } catch (DomainException exception) {
            ReservationOutcome outcome = outcome(exception);
            recordReservation(id, a.getName(), null, outcome, start);
            throw exception;
        } catch (IllegalArgumentException exception) {
            recordReservation(id, a.getName(), null, ReservationOutcome.INVALID_REQUEST, start);
            throw exception;
        } catch (RuntimeException exception) {
            recordReservation(id, a.getName(), null, ReservationOutcome.SERVER_ERROR, start);
            throw exception;
        }
    }

    @PostMapping("/reservations/{id}/cancel")
    @Operation(summary = "Cancel a reservation", description = "Requires the USER who owns the reservation.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ReservationView cancel(@PathVariable UUID id, Authentication a) {
        ReservationView cancelled = service.cancel(id, a.getName());
        metrics.recordCancellation();
        return cancelled;
    }

    private void recordReservation(UUID showId, String userId, UUID reservationId, ReservationOutcome outcome, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        metrics.recordReservationOutcome(outcome, Duration.ofNanos(System.nanoTime() - startNanos));
        String event = outcome == ReservationOutcome.CONFIRMED || outcome == ReservationOutcome.IDEMPOTENT_REPLAY
                ? "reservation_completed" : "reservation_declined";
        LOG.info("{}", "{\"event\":" + json(event)
                + ",\"request_id\":" + json(RequestIds.get())
                + ",\"user_id\":" + json(userId)
                + ",\"show_id\":" + json(showId.toString())
                + ",\"reservation_id\":" + json(reservationId == null ? null : reservationId.toString())
                + ",\"operation\":\"reserve\""
                + ",\"result\":" + json(outcome.name())
                + ",\"duration_ms\":" + durationMs + "}");
    }

    private static ReservationOutcome outcome(DomainException exception) {
        return switch (exception.code) {
            case "SEAT_TAKEN" -> ReservationOutcome.SEAT_TAKEN;
            case "PER_USER_LIMIT_EXCEEDED" -> ReservationOutcome.PER_USER_LIMIT;
            case "IDEMPOTENCY_CONFLICT" -> ReservationOutcome.IDEMPOTENCY_CONFLICT;
            default -> exception.status.is4xxClientError() ? ReservationOutcome.INVALID_REQUEST : ReservationOutcome.SERVER_ERROR;
        };
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
