package com.ticketbooking.system.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "show_seat")
public class ShowSeat {
    @Id
    public UUID id = UUID.randomUUID();
    @ManyToOne(optional = false)
    @JoinColumn(name = "show_id")
    public Show show;
    @Column(name = "seat_number")
    public String seatNumber;
    @Enumerated(EnumType.STRING)
    public SeatStatus status = SeatStatus.AVAILABLE;
    @ManyToOne
    @JoinColumn(name = "reservation_id")
    public Reservation reservation;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();

    protected ShowSeat() {
    }

    public ShowSeat(Show s, String n) {
        show = s;
        seatNumber = n;
    }
}
