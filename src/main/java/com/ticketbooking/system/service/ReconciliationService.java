package com.ticketbooking.system.service;

import com.ticketbooking.system.dto.Contracts.ReconciliationView;
import com.ticketbooking.system.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReconciliationView reconcile(UUID showId) {
        Integer exists = jdbc.queryForObject("select count(*) from shows where id=?", Integer.class, showId);
        if (exists == null || exists == 0) {
            throw new DomainException("SHOW_NOT_FOUND", HttpStatus.NOT_FOUND, "Show not found");
        }
        return jdbc.query("""
                select count(*) as total,
                       count(*) filter (where status='AVAILABLE') as available,
                       count(*) filter (where status='HELD') as held,
                       count(*) filter (where status='CONFIRMED') as confirmed
                from show_seat
                where show_id=?
                """, rs -> {
            rs.next();
            int total = rs.getInt("total");
            int available = rs.getInt("available");
            int held = rs.getInt("held");
            int confirmed = rs.getInt("confirmed");
            return new ReconciliationView(total, available, held, confirmed, available + held + confirmed == total);
        }, showId);
    }
}
