package com.ticketbooking.system.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class ObservabilityMetrics {
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Counter reservationAttempts;
    private final Counter reservationsConfirmed;
    private final Counter cancellations;
    private final Counter databaseErrors;
    private final Timer reservationDuration;

    public ObservabilityMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.reservationAttempts = Counter.builder("reservation_attempts_total")
                .description("Reservation attempts received by the service")
                .register(registry);
        this.reservationsConfirmed = Counter.builder("reservations_confirmed_total")
                .description("Reservation attempts that created a new confirmed reservation")
                .register(registry);
        this.cancellations = Counter.builder("reservations_cancelled_total")
                .description("Reservations cancelled")
                .register(registry);
        this.databaseErrors = Counter.builder("database_errors_total")
                .description("Database exceptions observed by the API")
                .register(registry);
        this.reservationDuration = Timer.builder("reservation_duration")
                .description("Reservation request duration")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("seats_available", this, ObservabilityMetrics::availableSeats)
                .description("Current count of AVAILABLE seats, reconciled from the database")
                .strongReference(true)
                .register(registry);
    }

    public void recordReservationAttempt() {
        reservationAttempts.increment();
    }

    public void recordReservationOutcome(ReservationOutcome outcome, Duration duration) {
        reservationDuration.record(duration.toNanos(), TimeUnit.NANOSECONDS);
        Counter.builder("reservation_outcomes_total")
                .description("Reservation attempts by exactly one bounded outcome")
                .tag("result", outcome.result())
                .register(registry)
                .increment();
        if (outcome == ReservationOutcome.CONFIRMED) {
            reservationsConfirmed.increment();
            return;
        }
        if (outcome.declineReason() != null) {
            Counter.builder("reservations_declined_total")
                    .description("Reservation attempts declined by low-cardinality reason")
                    .tag("reason", outcome.declineReason())
                    .register(registry)
                    .increment();
        }
    }

    public void recordCancellation() {
        cancellations.increment();
    }

    public void recordHttp(String method, String uri, int status) {
        Counter.builder("http_requests_total")
                .description("HTTP requests by method, route, and status family")
                .tag("method", method)
                .tag("uri", uri)
                .tag("status", String.valueOf(status))
                .tag("outcome", status / 100 + "xx")
                .register(registry)
                .increment();
        if (status >= 400 && status < 500) {
            Counter.builder("http_4xx_total").description("HTTP 4xx responses").register(registry).increment();
        } else if (status >= 500) {
            Counter.builder("http_5xx_total").description("HTTP 5xx responses").register(registry).increment();
        }
    }

    public void recordDatabaseError() {
        databaseErrors.increment();
    }

    private double availableSeats() {
        Integer count = jdbc.queryForObject("select count(*) from show_seat where status='AVAILABLE'", Integer.class);
        return count == null ? 0 : count;
    }
}
