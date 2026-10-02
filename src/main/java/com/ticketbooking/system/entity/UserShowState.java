package com.ticketbooking.system.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "user_show_state")
public class UserShowState {
    @Id
    public UUID id = UUID.randomUUID();
    @ManyToOne
    @JoinColumn(name = "show_id")
    public Show show;
    @Column(name = "user_id")
    public String userId;
    @Column(name = "booked_seat_count")
    public int bookedSeatCount;
}
