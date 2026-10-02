package com.ticketbooking.system.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class Show {
    @Id
    public UUID id = UUID.randomUUID();
    @Column(nullable = false)
    public String name;
    @Column(name = "price_paise", nullable = false)
    public long pricePaise;
    @Column(name = "per_user_limit", nullable = false)
    public int perUserLimit;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    protected Show() {
    }

    public Show(String n, long p, int l) {
        name = n;
        pricePaise = p;
        perUserLimit = l;
    }
}
