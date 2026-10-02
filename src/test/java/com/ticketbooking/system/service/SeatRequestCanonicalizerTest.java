package com.ticketbooking.system.service;

import com.ticketbooking.system.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeatRequestCanonicalizerTest {
    @Test
    void seatOrderAndCaseDoNotChangeTheCanonicalHash() {
        List<String> first = SeatRequestCanonicalizer.normalize(List.of("A13", "a12"));
        List<String> retry = SeatRequestCanonicalizer.normalize(List.of(" A12 ", "A13"));

        assertEquals(List.of("A12", "A13"), first);
        assertEquals(SeatRequestCanonicalizer.hash(first), SeatRequestCanonicalizer.hash(retry));
    }

    @Test
    void duplicateSeatsAfterNormalizationAreRejected() {
        DomainException error = assertThrows(DomainException.class,
                () -> SeatRequestCanonicalizer.normalize(List.of("a12", " A12 ")));

        assertEquals("INVALID_REQUEST", error.code);
    }
}
