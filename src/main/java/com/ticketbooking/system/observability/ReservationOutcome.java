package com.ticketbooking.system.observability;

public enum ReservationOutcome {
    CONFIRMED("confirmed", null),
    SEAT_TAKEN("seat_taken", "seat_taken"),
    PER_USER_LIMIT("per_user_limit", "per_user_limit"),
    IDEMPOTENT_REPLAY("idempotent_replay", "idempotent_replay"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict", "idempotency_conflict"),
    INVALID_REQUEST("other_4xx", "invalid_request"),
    SERVER_ERROR("5xx", null);

    private final String result;
    private final String declineReason;

    ReservationOutcome(String result, String declineReason) {
        this.result = result;
        this.declineReason = declineReason;
    }

    public String result() {
        return result;
    }

    public String declineReason() {
        return declineReason;
    }
}
