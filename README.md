# Event reservation service

Single Spring Boot 3 / Java 17 service backed by PostgreSQL. It uses Spring Data JPA for domain persistence, JDBC for the short atomic reservation statements, Flyway for schema ownership, JWT security, Actuator and Prometheus metrics.

## Run

`docker compose up --build` starts PostgreSQL and the application. Locally configure `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `JWT_SECRET`, then run `mvn clean test` and `mvn spring-boot:run`.

JWTs are HS256 tokens with a subject as the user id and a `roles` claim such as `["USER"]` or `["ADMIN"]`. `POST /shows` needs ADMIN. `POST /shows/{id}/reserve` and `POST /reservations/{id}/cancel` need USER. The JSON never accepts a user id.

Swagger UI is available at `/swagger-ui/index.html`; its OpenAPI document is `/v3/api-docs`. Supply JWTs with the bearer-auth control. `GET /health/live` reports whether the process is running, while `GET /health/ready` executes a PostgreSQL query and returns `503` when the database cannot be reached. Actuator health probes remain available under `/actuator/health`.

## API

* `POST /shows` creates a show with `name`, `seats`, `price_paise`, and `per_user_limit`.
* `GET /shows/{id}` reads a show.
* `POST /shows/{id}/reserve` accepts `seats` and `idempotency_key`.
* `POST /reservations/{id}/cancel` explicitly cancels the caller's reservation.

Errors contain `timestamp`, `request_id`, `status`, `code`, and `message`. The request filter accepts or generates `X-Request-Id`, returns it in the response, and adds it to logging MDC.

The error contract uses `AUTHENTICATION_REQUIRED` (401), `FORBIDDEN` (403), `SHOW_NOT_FOUND` and `RESERVATION_NOT_FOUND` (404), `SEAT_TAKEN`, `PER_USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, and `INVALID_RESERVATION_STATE` (409), and `VALIDATION_ERROR`, `INVALID_SEAT`, or `INVALID_REQUEST` (400). Unexpected failures produce `INTERNAL_ERROR` without stack traces. Each completed request also emits a JSON log record with request metadata and duration; credentials and request bodies are never logged.

## Database schema

`shows` owns the integer paise price and per-user cap. `show_seat` has one row per physical seat and records its current owner. `reservation` records the immutable price calculation and status; `reservation_seat` is the reservation-to-seat audit mapping. `idempotency_key` stores the request hash and final reservation. `user_show_state` stores the active seat count for a `(show_id,user_id)` pair.

The migration adds foreign keys, enum check constraints, nonnegative price and amount checks, a positive cap check, unique `(show_id, seat_number)`, unique `(show_id, user_id, idempotency_key)`, and unique reservation-seat pairs.

## Concurrency Design

Every reservation is all-or-nothing. A request for `["A12", "A13"]` reserves neither seat if either seat is unavailable and returns `409 SEAT_TAKEN`.

The atomic decision is inside `ReservationService.reserve`'s single Spring transaction. It validates and canonicalizes seat names, then takes PostgreSQL `SELECT ... FOR UPDATE` locks in one global order: first the caller's `(show_id, user_id)` `user_show_state` row, then every requested `show_seat` row in ascending `seat_number` order. Only after all locks are held does it check the canonical request hash/idempotency record, seat state, and per-user count, create the reservation, mark seats `CONFIRMED`, create the audit links, and update the count.

The final seat update is conditional on `status = 'AVAILABLE'` and must update every requested row. Therefore concurrent contenders cannot double-sell: the first transaction holds and changes the locked row; followers see `CONFIRMED` and receive `409 SEAT_TAKEN`. The state-row lock serializes all seat requests for one user/show, so concurrent requests cannot collectively exceed `per_user_limit`.

The database has a unique `(show_id, user_id, idempotency_key)` constraint: idempotency is explicitly scoped to **show + authenticated user + key**. Requests hash the sorted, normalized seat list; `["A12", "A13"]` and `["A13", "A12"]` are equivalent. A repeated key with the same hash returns the original reservation; a different hash returns `409 IDEMPOTENCY_CONFLICT`. Different users may safely use the same key. The unique constraint remains the race backstop even if another caller bypasses normal application flow.

Cancellation is also transactional and follows the same order: user-show state, then (only for cancellation) its reservation row, then seat rows in ascending `seat_number`. It verifies each `CONFIRMED` seat is still owned by that reservation, and only then releases them. It never clears a seat based solely on a reservation id, so a cancellation cannot overwrite a later reservation. Any exception or failed check rolls back the complete transaction and releases PostgreSQL locks; no partial reservation, seat state, count, or idempotency result remains committed. Time-based HELD-seat expiration is intentionally a next-phase mechanism; this version supports explicit cancellation only.
