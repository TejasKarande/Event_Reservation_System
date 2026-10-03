package com.ticketbooking.system.controller;

import com.ticketbooking.system.dto.Contracts.*;
import com.ticketbooking.system.service.ReconciliationService;
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
    private final ReconciliationService reconciliation;

    public ShowController(ReservationService s, ReconciliationService reconciliation) {
        service = s;
        this.reconciliation = reconciliation;
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

    @GetMapping("/{id}/reconciliation")
    @Operation(summary = "Reconcile show seat counts", description = "Returns database-derived total, available, held, and confirmed counts.")
    public ReconciliationView reconciliation(@PathVariable UUID id) {
        return reconciliation.reconcile(id);
    }
}
