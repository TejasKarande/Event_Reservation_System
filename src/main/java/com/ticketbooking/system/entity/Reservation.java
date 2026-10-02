package com.ticketbooking.system.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservation")
public class Reservation {
    @Id
    public UUID id = UUID.randomUUID();
    @ManyToOne(optional = false)
    @JoinColumn(name = "show_id")
    public Show show;
    @Column(name = "user_id")
    public String userId;
    @Column(name = "amount_paise")
    public long amountPaise;
    @Enumerated(EnumType.STRING)
    public ReservationStatus status = ReservationStatus.CONFIRMED;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();

    protected Reservation() {
    }

    public Reservation(Show s, String u, long a) {
        show = s;
        userId = u;
        amountPaise = a;
    }
}
