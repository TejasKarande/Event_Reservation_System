package com.ticketbooking.system.controller;

import com.ticketbooking.system.dto.Contracts.*;
import com.ticketbooking.system.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/shows")
public class ShowController {
    private final ReservationService service;

    public ShowController(ReservationService s) {
        service = s;
    }

    @PostMapping
    @Operation(summary = "Create a show", description = "Requires ADMIN. Seat names are normalized and must be unique.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ShowView> create(@Valid @RequestBody CreateShow r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get show availability")
    public ShowView show(@PathVariable UUID id) {
        return service.show(id);
    }
}
