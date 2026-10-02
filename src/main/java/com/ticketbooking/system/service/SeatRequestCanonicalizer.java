package com.ticketbooking.system.service;

import com.ticketbooking.system.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class SeatRequestCanonicalizer {
    private SeatRequestCanonicalizer() {
    }

    static List<String> normalize(List<String> requestedSeats) {
        Set<String> sorted = new TreeSet<>();
        for (String requested : requestedSeats) {
            String normalized = requested.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty() || !sorted.add(normalized)) {
                throw new DomainException("INVALID_REQUEST", HttpStatus.BAD_REQUEST,
                        normalized.isEmpty() ? "Seat name must not be blank" : "Duplicate seat numbers are not allowed");
            }
        }
        return List.copyOf(sorted);
    }

    static String hash(List<String> normalizedSeats) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", normalizedSeats).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
