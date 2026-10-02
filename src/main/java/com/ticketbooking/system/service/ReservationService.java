package com.ticketbooking.system.service;

import com.ticketbooking.system.dto.Contracts.CreateShow;
import com.ticketbooking.system.dto.Contracts.ReservationView;
import com.ticketbooking.system.dto.Contracts.Reserve;
import com.ticketbooking.system.dto.Contracts.SeatView;
import com.ticketbooking.system.dto.Contracts.ShowView;
import com.ticketbooking.system.entity.Reservation;
import com.ticketbooking.system.entity.ReservationStatus;
import com.ticketbooking.system.entity.Show;
import com.ticketbooking.system.exception.DomainException;
import com.ticketbooking.system.repository.ReservationRepository;
import com.ticketbooking.system.repository.ShowRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ReservationService {
    private final ShowRepository shows;
    private final ReservationRepository reservations;
    private final JdbcTemplate db;

    public ReservationService(ShowRepository shows, ReservationRepository reservations, JdbcTemplate db) {
        this.shows = shows;
        this.reservations = reservations;
        this.db = db;
    }

    @Transactional
    public ShowView create(CreateShow request) {
        List<String> seats = SeatRequestCanonicalizer.normalize(request.seats());
        Show show = shows.saveAndFlush(new Show(request.name().trim(), request.price_paise(), request.per_user_limit()));
        for (String seat : seats) {
            db.update("insert into show_seat(id,show_id,seat_number,status,created_at,updated_at) "
                    + "values(?,?,?,'AVAILABLE',now(),now())", UUID.randomUUID(), show.id, seat);
        }
        return showView(show, seats.stream().map(seat -> new SeatView(seat, "AVAILABLE")).toList());
    }

    @Transactional(readOnly = true)
    public ShowView show(UUID id) {
        Show show = shows.findById(id).orElseThrow(() -> notFound("SHOW_NOT_FOUND", "Show not found"));
        List<SeatView> seats = db.query("select seat_number,status from show_seat where show_id=? order by seat_number",
                (rs, row) -> new SeatView(rs.getString("seat_number"), rs.getString("status")), id);
        return showView(show, seats);
    }

    /** The atomic decision occurs only after the state and every seat row are locked. */
    @Transactional
    public ReservationView reserve(UUID showId, String userId, Reserve request) {
        List<String> seats = SeatRequestCanonicalizer.normalize(request.seats());
        String idempotencyKey = request.idempotency_key().trim();
        Show show = shows.findById(showId).orElseThrow(() -> notFound("SHOW_NOT_FOUND", "Show not found"));

        // Global order: user_show_state, then show_seat rows ordered by seat_number.
        ensureUserShowState(showId, userId);
        int bookedSeatCount = lockBookedSeatCount(showId, userId);
        List<Map<String, Object>> lockedSeats = lockSeats(showId, seats);
        if (lockedSeats.size() != seats.size()) {
            throw invalid("INVALID_SEAT", "One or more requested seats do not exist for this show");
        }

        String requestHash = SeatRequestCanonicalizer.hash(seats);
        Map<String, Object> previous = findIdempotency(showId, userId, idempotencyKey);
        if (previous != null) {
            if (!requestHash.equals(previous.get("request_hash"))) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency key was used with another request");
            }
            UUID reservationId = (UUID) previous.get("reservation_id");
            if (reservationId == null) {
                throw conflict("IDEMPOTENCY_CONFLICT", "The idempotent request is still being processed");
            }
            return view(reservationId);
        }

        for (Map<String, Object> seat : lockedSeats) {
            if (!"AVAILABLE".equals(seat.get("status"))) {
                throw conflict("SEAT_TAKEN", "One or more requested seats are unavailable");
            }
        }
        if (bookedSeatCount + seats.size() > show.perUserLimit) {
            throw conflict("PER_USER_LIMIT_EXCEEDED", "Per-user seat limit exceeded");
        }

        UUID idempotencyId = UUID.randomUUID();
        int claimedKey = db.update("insert into idempotency_key(id,show_id,user_id,idempotency_key,request_hash,created_at) "
                + "values(?,?,?,?,?,now()) on conflict(show_id,user_id,idempotency_key) do nothing", idempotencyId, showId,
                userId, idempotencyKey, requestHash);
        if (claimedKey != 1) {
            Map<String, Object> raced = findIdempotency(showId, userId, idempotencyKey);
            if (raced != null && requestHash.equals(raced.get("request_hash")) && raced.get("reservation_id") != null) {
                return view((UUID) raced.get("reservation_id"));
            }
            throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency key was used with another request");
        }

        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.pricePaise, (long) seats.size());
        db.update("insert into reservation(id,show_id,user_id,amount_paise,status,created_at,updated_at) "
                + "values(?,?,?,?, 'CONFIRMED',now(),now())", reservationId, showId, userId, amount);
        int confirmed = db.update("update show_seat set status='CONFIRMED',reservation_id=?,updated_at=now() "
                + "where show_id=? and status='AVAILABLE' and seat_number in (" + placeholders(seats.size()) + ")",
                updateArguments(reservationId, showId, seats));
        if (confirmed != seats.size()) {
            throw conflict("SEAT_TAKEN", "One or more requested seats are unavailable");
        }
        for (Map<String, Object> seat : lockedSeats) {
            db.update("insert into reservation_seat(reservation_id,show_seat_id,seat_number) values(?,?,?)", reservationId,
                    seat.get("id"), seat.get("seat_number"));
        }
        db.update("update user_show_state set booked_seat_count=?,updated_at=now() where show_id=? and user_id=?",
                bookedSeatCount + seats.size(), showId, userId);
        db.update("update idempotency_key set reservation_id=? where id=?", reservationId, idempotencyId);
        return view(reservationId);
    }

    @Transactional
    public ReservationView cancel(UUID reservationId, String userId) {
        Reservation snapshot = reservations.findById(reservationId)
                .orElseThrow(() -> notFound("RESERVATION_NOT_FOUND", "Reservation not found"));
        if (!snapshot.userId.equals(userId)) {
            throw forbidden("FORBIDDEN", "You do not own this reservation");
        }

        // Cancellation uses the same state-before-seat order as reservation.
        ensureUserShowState(snapshot.show.id, userId);
        int bookedSeatCount = lockBookedSeatCount(snapshot.show.id, userId);
        Reservation reservation = reservations.findLockedById(reservationId)
                .orElseThrow(() -> notFound("RESERVATION_NOT_FOUND", "Reservation not found"));
        if (reservation.status == ReservationStatus.CANCELLED) {
            return view(reservationId);
        }

        List<String> seats = db.queryForList(
                "select seat_number from reservation_seat where reservation_id=? order by seat_number", String.class, reservationId);
        List<Map<String, Object>> lockedSeats = lockSeats(reservation.show.id, seats);
        if (lockedSeats.size() != seats.size() || lockedSeats.stream().anyMatch(seat ->
                !"CONFIRMED".equals(seat.get("status")) || !reservationId.equals(seat.get("reservation_id")))) {
            throw conflict("INVALID_RESERVATION_STATE", "Reservation no longer owns all of its seats");
        }

        int released = db.update("update show_seat set status='AVAILABLE',reservation_id=null,updated_at=now() "
                + "where reservation_id=? and status='CONFIRMED'", reservationId);
        if (released != seats.size()) {
            throw conflict("INVALID_RESERVATION_STATE", "Reservation no longer owns all of its seats");
        }
        db.update("update user_show_state set booked_seat_count=?,updated_at=now() where show_id=? and user_id=?",
                bookedSeatCount - released, reservation.show.id, userId);
        reservation.status = ReservationStatus.CANCELLED;
        reservation.updatedAt = Instant.now();
        reservations.flush();
        return view(reservationId);
    }

    private ShowView showView(Show show, List<SeatView> seats) {
        int available = (int) seats.stream().filter(seat -> "AVAILABLE".equals(seat.status())).count();
        int held = (int) seats.stream().filter(seat -> "HELD".equals(seat.status())).count();
        int confirmed = (int) seats.stream().filter(seat -> "CONFIRMED".equals(seat.status())).count();
        return new ShowView(show.id, show.name, show.pricePaise, show.perUserLimit, seats.size(), available, held, confirmed,
                List.copyOf(seats));
    }

    private void ensureUserShowState(UUID showId, String userId) {
        db.update("insert into user_show_state(id,show_id,user_id,booked_seat_count,created_at,updated_at) "
                + "values(?,?,?,0,now(),now()) on conflict(show_id,user_id) do nothing", UUID.randomUUID(), showId, userId);
    }

    private int lockBookedSeatCount(UUID showId, String userId) {
        return db.queryForObject("select booked_seat_count from user_show_state where show_id=? and user_id=? for update",
                Integer.class, showId, userId);
    }

    private List<Map<String, Object>> lockSeats(UUID showId, List<String> seats) {
        return db.queryForList("select id,seat_number,status,reservation_id from show_seat where show_id=? and seat_number in ("
                + placeholders(seats.size()) + ") order by seat_number for update", seatSelectArguments(showId, seats));
    }

    private Map<String, Object> findIdempotency(UUID showId, String userId, String key) {
        List<Map<String, Object>> results = db.queryForList(
                "select request_hash,reservation_id from idempotency_key where show_id=? and user_id=? and idempotency_key=?",
                showId, userId, key);
        return results.isEmpty() ? null : results.get(0);
    }

    private ReservationView view(UUID reservationId) {
        Map<String, Object> reservation = db.queryForMap(
                "select id,show_id,user_id,amount_paise,status from reservation where id=?", reservationId);
        List<String> seats = db.queryForList(
                "select seat_number from reservation_seat where reservation_id=? order by seat_number", String.class, reservationId);
        return new ReservationView((UUID) reservation.get("id"), (UUID) reservation.get("show_id"),
                (String) reservation.get("user_id"), List.copyOf(seats),
                ((Number) reservation.get("amount_paise")).longValue(), ((String) reservation.get("status")).toLowerCase(Locale.ROOT));
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private static Object[] seatSelectArguments(UUID showId, List<String> seats) {
        List<Object> args = new ArrayList<>();
        args.add(showId);
        args.addAll(seats);
        return args.toArray();
    }

    private static Object[] updateArguments(UUID reservationId, UUID showId, List<String> seats) {
        List<Object> args = new ArrayList<>();
        args.add(reservationId);
        args.add(showId);
        args.addAll(seats);
        return args.toArray();
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(code, HttpStatus.BAD_REQUEST, message);
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(code, HttpStatus.NOT_FOUND, message);
    }

    private static DomainException forbidden(String code, String message) {
        return new DomainException(code, HttpStatus.FORBIDDEN, message);
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(code, HttpStatus.CONFLICT, message);
    }
}
