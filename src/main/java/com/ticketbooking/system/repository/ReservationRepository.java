package com.ticketbooking.system.repository;

import com.ticketbooking.system.entity.Reservation;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id=:id")
    Optional<Reservation> findLockedById(UUID id);
}
