package com.ticketbooking.system.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

import java.time.Instant;
import java.util.Map;

/** Lightweight process and database probes for load balancers. */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health/live")
    @Operation(summary = "Liveness probe")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "timestamp", Instant.now().toString());
    }

    @GetMapping("/health/ready")
    @Operation(summary = "Readiness probe", description = "Verifies PostgreSQL connectivity and returns 503 when it is unavailable.")
    public ResponseEntity<Map<String, Object>> ready() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "timestamp", Instant.now().toString()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "timestamp", Instant.now().toString()));
        }
    }
}
