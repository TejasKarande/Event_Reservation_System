package com.ticketbooking.system.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public final class Contracts {
    private Contracts() {
    }

    public record CreateShow(@NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 30) String> seats,
            @PositiveOrZero long price_paise, @Positive int per_user_limit) {
    }

    public record Reserve(@NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 30) String> seats,
            @NotBlank @Size(max = 200) String idempotency_key) {
    }

    public record SeatView(String seat, String status) {
    }

    public record ShowView(UUID id, String name, long price_paise, int per_user_limit, int total_seats,
            int available_seats, int held_seats, int confirmed_seats, List<SeatView> seats) {
    }

    public record ReservationView(UUID reservation_id, UUID show_id, String user_id, List<String> seats,
            long amount_paise, String status) {
    }
}
