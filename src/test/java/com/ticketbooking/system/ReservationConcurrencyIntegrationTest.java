package com.ticketbooking.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.system.dto.Contracts.CreateShow;
import com.ticketbooking.system.dto.Contracts.ShowView;
import com.ticketbooking.system.service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Testcontainers(disabledWithoutDocker = true)
class ReservationConcurrencyIntegrationTest {
    private static final String JWT_SECRET = "integration-test-secret-must-be-at-least-thirty-two-bytes";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ReservationService reservations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    @AfterEach
    void clearDatabase() {
        jdbc.execute("truncate table reservation_seat, idempotency_key, show_seat, reservation, user_show_state, shows cascade");
    }

    @Test
    void adminCreatesShowWithAvailableSeats() throws Exception {
        String request = json.writeValueAsString(java.util.Map.of(
                "name", "friday-night",
                "seats", List.of("A1", "A2"),
                "price_paise", 25_000,
                "per_user_limit", 4));

        MvcResult result = mvc.perform(post("/shows").contentType("application/json").content(request)
                .header("Authorization", token("admin", "ADMIN"))).andReturn();

        assertEquals(201, result.getResponse().getStatus());
        assertEquals(2, body(result).path("available_seats").asInt());
        MvcResult userResult = mvc.perform(post("/shows").contentType("application/json").content(request)
                .header("Authorization", token("user", "USER"))).andExpect(status().isForbidden()).andReturn();
        assertEquals("FORBIDDEN", errorCode(userResult));
    }

    @Test
    void reservationRequiresAValidUnexpiredJwt() throws Exception {
        UUID show = show(4, "A1");
        String request = json.writeValueAsString(java.util.Map.of("seats", List.of("A1"), "idempotency_key", "key"));

        MvcResult missing = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json").content(request))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult malformed = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json").content(request)
                .header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized()).andReturn();
        MvcResult expired = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json").content(request)
                .header("Authorization", expiredToken("user-a", "USER"))).andExpect(status().isUnauthorized()).andReturn();

        assertEquals("AUTHENTICATION_REQUIRED", errorCode(missing));
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(malformed));
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(expired));
    }

    @Test
    void cancellationRequiresJwt() throws Exception {
        UUID reservationId = UUID.fromString(body(reserve(show(4, "A1"), "user-a", List.of("A1"), "reserve"))
                .path("reservation_id").asText());

        MvcResult result = mvc.perform(post("/reservations/{id}/cancel", reservationId))
                .andExpect(status().isUnauthorized()).andReturn();

        assertEquals("AUTHENTICATION_REQUIRED", errorCode(result));
    }

    @Test
    void jwtSubjectWinsOverSpoofedUserIdInRequestBody() throws Exception {
        UUID show = show(4, "A12");
        String request = json.writeValueAsString(java.util.Map.of("user_id", "user-b", "seats", List.of("A12"),
                "idempotency_key", "spoof-attempt"));

        MvcResult result = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json").content(request)
                .header("Authorization", token("user-a", "USER"))).andExpect(status().isCreated()).andReturn();

        assertEquals("user-a", body(result).path("user_id").asText());
        assertEquals("user-a", jdbc.queryForObject("select user_id from reservation", String.class));
    }

    @Test
    void twoUsersCompetingForOneSeatProduceOneWinner() throws Exception {
        UUID show = show(4, "A1");
        List<MvcResult> results = concurrently(List.of(
                () -> reserve(show, "user-a", List.of("A1"), "a"),
                () -> reserve(show, "user-b", List.of("A1"), "b")));

        assertEquals(1, countStatus(results, 201));
        assertEquals(1, countStatus(results, 409));
        assertEquals("SEAT_TAKEN", errorCode(firstStatus(results, 409)));
    }

    @Test
    void oneHundredConcurrentRequestsForOneSeatHaveNoServerErrors() throws Exception {
        UUID show = show(4, "A1");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int index = i;
            calls.add(() -> reserve(show, "user-" + index, List.of("A1"), "key-" + index));
        }
        List<MvcResult> results = concurrently(calls);

        assertEquals(1, countStatus(results, 201));
        assertEquals(99, countStatus(results, 409));
        assertEquals(0, countStatus(results, 500));
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 409) {
                assertEquals("SEAT_TAKEN", errorCode(result));
            }
        }
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalReservation() throws Exception {
        UUID show = show(4, "A12", "A13");
        MvcResult first = reserve(show, "user-a", List.of("A13", "A12"), "repeat-key");
        MvcResult retry = reserve(show, "user-a", List.of("A12", "A13"), "repeat-key");

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, retry.getResponse().getStatus());
        assertEquals(body(first).path("reservation_id").asText(), body(retry).path("reservation_id").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from reservation", Integer.class));
    }

    @Test
    void successfulReservationIncrementsConfirmationCounter() throws Exception {
        UUID show = show(4, "A1");
        double before = counter("reservations_confirmed_total");

        MvcResult result = reserve(show, "user-a", List.of("A1"), "metrics-success");

        assertEquals(201, result.getResponse().getStatus());
        assertEquals(before + 1, counter("reservations_confirmed_total"));
    }

    @Test
    void seatConflictIncrementsSeatTakenDeclineMetric() throws Exception {
        UUID show = show(4, "A1");
        reserve(show, "user-a", List.of("A1"), "winner");
        double beforeSeatTaken = counter("reservations_declined_total", "reason", "seat_taken");
        double before5xx = counter("http_5xx_total");

        MvcResult result = reserve(show, "user-b", List.of("A1"), "loser");

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("SEAT_TAKEN", errorCode(result));
        assertEquals(beforeSeatTaken + 1, counter("reservations_declined_total", "reason", "seat_taken"));
        assertEquals(before5xx, counter("http_5xx_total"));
    }

    @Test
    void perUserLimitIncrementsCorrespondingDeclineMetric() throws Exception {
        UUID show = show(1, "A1", "A2");
        reserve(show, "user-a", List.of("A1"), "first");
        double before = counter("reservations_declined_total", "reason", "per_user_limit");

        MvcResult result = reserve(show, "user-a", List.of("A2"), "second");

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("PER_USER_LIMIT_EXCEEDED", errorCode(result));
        assertEquals(before + 1, counter("reservations_declined_total", "reason", "per_user_limit"));
    }

    @Test
    void idempotentReplayIsObservableAndDoesNotCreateAnotherReservation() throws Exception {
        UUID show = show(4, "A1", "A2");
        MvcResult first = reserve(show, "user-a", List.of("A1"), "replay");
        double beforeReplay = counter("reservations_declined_total", "reason", "idempotent_replay");
        double beforeConfirmed = counter("reservations_confirmed_total");

        MvcResult retry = reserve(show, "user-a", List.of("A1"), "replay");

        assertEquals(201, retry.getResponse().getStatus());
        assertEquals(body(first).path("reservation_id").asText(), body(retry).path("reservation_id").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from reservation where show_id=?", Integer.class, show));
        assertEquals(beforeReplay + 1, counter("reservations_declined_total", "reason", "idempotent_replay"));
        assertEquals(beforeConfirmed, counter("reservations_confirmed_total"));
    }

    @Test
    void availabilityGaugeAndReconciliationMatchDatabaseState() throws Exception {
        UUID show = show(4, "A1", "A2", "A3");
        reserve(show, "user-a", List.of("A1", "A2"), "gauge");
        int databaseAvailable = jdbc.queryForObject("select count(*) from show_seat where status='AVAILABLE'", Integer.class);

        MvcResult reconciliation = mvc.perform(get("/shows/{id}/reconciliation", show)).andExpect(status().isOk()).andReturn();

        assertEquals(databaseAvailable, (int) meterRegistry.get("seats_available").gauge().value());
        assertEquals(3, body(reconciliation).path("total").asInt());
        assertEquals(1, body(reconciliation).path("available").asInt());
        assertEquals(2, body(reconciliation).path("confirmed").asInt());
        assertTrue(body(reconciliation).path("balanced").asBoolean());
    }

    @Test
    void prometheusEndpointIsAvailableToScrapers() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void fiftyConcurrentRetriesWithOneIdempotencyKeyReturnOneReservation() throws Exception {
        UUID show = show(4, "A12");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(() -> reserve(show, "user-a", List.of("A12"), "one-logical-request"));
        }
        List<MvcResult> results = concurrently(calls);

        assertEquals(50, countStatus(results, 201));
        assertEquals(0, countStatus(results, 500));
        String reservationId = body(results.get(0)).path("reservation_id").asText();
        for (MvcResult result : results) {
            assertEquals(reservationId, body(result).path("reservation_id").asText());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from reservation", Integer.class));
    }

    @Test
    void idempotencyKeyWithDifferentCanonicalBodyIsAConflict() throws Exception {
        UUID show = show(4, "A12", "A13");
        reserve(show, "user-a", List.of("A12"), "same-key");
        MvcResult retry = reserve(show, "user-a", List.of("A13"), "same-key");

        assertEquals(409, retry.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", errorCode(retry));
    }

    @Test
    void idempotencyKeyIsScopedToShowAndAuthenticatedUser() throws Exception {
        UUID show = show(4, "A12", "A13");
        MvcResult first = reserve(show, "user-a", List.of("A12"), "shared-key");
        MvcResult second = reserve(show, "user-b", List.of("A13"), "shared-key");

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertEquals(2, jdbc.queryForObject("select count(*) from reservation", Integer.class));
    }

    @Test
    void oneUserCannotExceedLimitUnderConcurrency() throws Exception {
        UUID show = show(4, "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            int index = i;
            calls.add(() -> reserve(show, "user-a", List.of("A" + index), "key-" + index));
        }
        List<MvcResult> results = concurrently(calls);

        assertEquals(4, countStatus(results, 201));
        assertEquals(6, countStatus(results, 409));
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 409) {
                assertEquals("PER_USER_LIMIT_EXCEEDED", errorCode(result));
            }
        }
        assertEquals(4, jdbc.queryForObject(
                "select booked_seat_count from user_show_state where show_id=? and user_id='user-a'", Integer.class, show));
    }

    @Test
    void multiSeatRequestDoesNotPartiallyReserveWhenOneSeatIsTaken() throws Exception {
        UUID show = show(4, "A12", "A13");
        reserve(show, "user-a", List.of("A12"), "one");
        MvcResult result = reserve(show, "user-b", List.of("A12", "A13"), "two");

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("SEAT_TAKEN", errorCode(result));
        assertEquals("AVAILABLE", jdbc.queryForObject(
                "select status from show_seat where show_id=? and seat_number='A13'", String.class, show));
    }

    @Test
    void ownerCanCancelAndNonOwnerCannot() throws Exception {
        UUID show = show(4, "A1");
        MvcResult reserved = reserve(show, "user-a", List.of("A1"), "reserve");
        UUID reservationId = UUID.fromString(body(reserved).path("reservation_id").asText());

        mvc.perform(post("/reservations/{id}/cancel", reservationId).header("Authorization", token("user-b", "USER")))
                .andExpect(status().isForbidden());
        MvcResult cancelled = mvc.perform(post("/reservations/{id}/cancel", reservationId)
                .header("Authorization", token("user-a", "USER"))).andReturn();

        assertEquals(200, cancelled.getResponse().getStatus());
        assertEquals("cancelled", body(cancelled).path("status").asText());
        assertEquals("AVAILABLE", jdbc.queryForObject(
                "select status from show_seat where show_id=? and seat_number='A1'", String.class, show));
    }

    @Test
    void validationErrorsUseApiErrorContract() throws Exception {
        String invalidShow = json.writeValueAsString(java.util.Map.of(
                "name", " ",
                "seats", List.of("A1"),
                "price_paise", -1,
                "per_user_limit", 0));
        MvcResult showResult = mvc.perform(post("/shows").contentType("application/json").content(invalidShow)
                .header("Authorization", token("admin", "ADMIN"))).andExpect(status().isBadRequest()).andReturn();
        assertEquals("VALIDATION_ERROR", errorCode(showResult));

        UUID show = show(4, "A1");
        String duplicateSeats = json.writeValueAsString(java.util.Map.of("seats", List.of("A1", " a1 "),
                "idempotency_key", "dup"));
        MvcResult duplicateResult = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json")
                .content(duplicateSeats).header("Authorization", token("user-a", "USER")))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("INVALID_REQUEST", errorCode(duplicateResult));

        String missingKey = json.writeValueAsString(java.util.Map.of("seats", List.of("A1")));
        MvcResult missingKeyResult = mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json")
                .content(missingKey).header("Authorization", token("user-a", "USER")))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("VALIDATION_ERROR", errorCode(missingKeyResult));
    }

    @Test
    void badIdentifiersAndMissingResourcesUseMappedErrors() throws Exception {
        MvcResult badShowId = mvc.perform(get("/shows/not-a-uuid")).andExpect(status().isBadRequest()).andReturn();
        assertEquals("INVALID_REQUEST", errorCode(badShowId));

        MvcResult missingShow = mvc.perform(get("/shows/{id}", UUID.randomUUID())).andExpect(status().isNotFound()).andReturn();
        assertEquals("SHOW_NOT_FOUND", errorCode(missingShow));

        MvcResult missingReservation = mvc.perform(post("/reservations/{id}/cancel", UUID.randomUUID())
                .header("Authorization", token("user-a", "USER"))).andExpect(status().isNotFound()).andReturn();
        assertEquals("RESERVATION_NOT_FOUND", errorCode(missingReservation));
    }

    @Test
    void healthEndpointsReportLivenessAndDatabaseReadiness() throws Exception {
        mvc.perform(get("/health/live")).andExpect(status().isOk());
        mvc.perform(get("/health/ready")).andExpect(status().isOk());
    }

    @Test
    void concurrentCancellationAndReservationNeverResurrectsTheOldOwner() throws Exception {
        UUID show = show(4, "A1");
        UUID reservationId = UUID.fromString(body(reserve(show, "user-a", List.of("A1"), "first"))
                .path("reservation_id").asText());
        List<MvcResult> results = concurrently(List.of(
                () -> mvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .header("Authorization", token("user-a", "USER"))).andReturn(),
                () -> reserve(show, "user-b", List.of("A1"), "second")));

        assertEquals(0, countStatus(results, 500));
        String seatStatus = jdbc.queryForObject("select status from show_seat where show_id=? and seat_number='A1'", String.class,
                show);
        Object owner = jdbc.queryForObject("select reservation_id from show_seat where show_id=? and seat_number='A1'",
                Object.class, show);
        assertTrue("AVAILABLE".equals(seatStatus) || "CONFIRMED".equals(seatStatus));
        assertTrue(("AVAILABLE".equals(seatStatus) && owner == null) || ("CONFIRMED".equals(seatStatus) && owner != null));
        assertFalse(jdbc.queryForObject("select status from reservation where id=?", String.class, reservationId)
                .equals("CONFIRMED"));
    }

    @Test
    void showResponseReconcilesEverySeatCount() throws Exception {
        UUID show = show(4, "A1", "A2", "A3", "A4", "A5");
        reserve(show, "user-a", List.of("A1", "A2"), "a");
        reserve(show, "user-b", List.of("A3"), "b");

        ShowView response = reservations.show(show);
        assertEquals(response.total_seats(), response.available_seats() + response.held_seats() + response.confirmed_seats());
        assertEquals(5, response.seats().size());
        assertEquals(3, response.confirmed_seats());
    }

    private UUID show(int limit, String... seats) {
        return reservations.create(new CreateShow("show-" + UUID.randomUUID(), List.of(seats), 25_000, limit)).id();
    }

    private MvcResult reserve(UUID show, String user, List<String> seats, String key) throws Exception {
        String request = json.writeValueAsString(java.util.Map.of("seats", seats, "idempotency_key", key));
        return mvc.perform(post("/shows/{id}/reserve", show).contentType("application/json").content(request)
                .header("Authorization", token(user, "USER"))).andReturn();
    }

    private List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(calls.size(), 24));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String token(String subject, String role) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(subject).claim("roles", List.of(role)).signWith(key).compact();
    }

    private String expiredToken(String subject, String role) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(subject).claim("roles", List.of(role)).expiration(new Date(0))
                .signWith(key).compact();
    }

    private int countStatus(List<MvcResult> results, int expectedStatus) {
        return (int) results.stream().filter(result -> result.getResponse().getStatus() == expectedStatus).count();
    }

    private MvcResult firstStatus(List<MvcResult> results, int expectedStatus) {
        return results.stream().filter(result -> result.getResponse().getStatus() == expectedStatus).findFirst().orElseThrow();
    }

    private String errorCode(MvcResult result) throws Exception {
        return body(result).path("code").asText();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private double counter(String name, String... tags) {
        var search = meterRegistry.find(name);
        if (tags.length > 0) {
            search.tags(tags);
        }
        var counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }
}
