package com.ticketbooking.system.controller;

import com.ticketbooking.system.dto.Contracts.*;

import com.ticketbooking.system.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService s) {
        service = s;
    }

    @PostMapping("/shows/{id}/reserve")
    @Operation(summary = "Reserve seats", description = "Requires USER. The JWT subject is the owner; multi-seat reservations are all-or-nothing. Idempotency is scoped to show + user + key.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReservationView> reserve(@PathVariable UUID id, @Valid @RequestBody Reserve r,
            Authentication a) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(id, a.getName(), r));
    }

    @PostMapping("/reservations/{id}/cancel")
    @Operation(summary = "Cancel a reservation", description = "Requires the USER who owns the reservation.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ReservationView cancel(@PathVariable UUID id, Authentication a) {
        return service.cancel(id, a.getName());
    }
}
