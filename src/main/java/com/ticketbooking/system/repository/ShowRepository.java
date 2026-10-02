package com.ticketbooking.system.repository;

import com.ticketbooking.system.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}
