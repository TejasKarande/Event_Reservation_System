# Event reservation service

Single Spring Boot 3 / Java 17 service backed by PostgreSQL. It uses Spring Data JPA for domain persistence, JDBC for the short atomic reservation statements, Flyway for schema ownership, JWT security, Actuator and Prometheus metrics.

## Run

`docker compose up --build` starts PostgreSQL and the application from a clean checkout. The compose file does not contain committed passwords or JWT secrets, so set a local-only JWT secret first:

```powershell
$env:JWT_SECRET = "<set-a-local-jwt-secret-at-least-32-bytes>"
docker compose up --build
```

```bash
export JWT_SECRET="<set-a-local-jwt-secret-at-least-32-bytes>"
docker compose up --build
```

PostgreSQL is exposed on `localhost:5432` and the service listens on `http://localhost:8080` by default. For local compose, PostgreSQL uses host trust authentication so no password needs to be committed. For any shared or deployed environment, set a real `DATABASE_PASSWORD` and use the platform's managed PostgreSQL credentials.

Run the tests:

```bash
mvn clean test
```

Build the container:

```bash
docker build -t event-reservation-service .
```

Run the container against an existing PostgreSQL database:

```bash
docker run --rm -p 8080:8080 \
  -e PORT=8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/ticketbooking \
  -e DATABASE_USERNAME=ticketbooking \
  -e DATABASE_PASSWORD="$DATABASE_PASSWORD" \
  -e JWT_SECRET="$JWT_SECRET" \
  event-reservation-service
```

On PowerShell:

```powershell
docker run --rm -p 8080:8080 `
  -e PORT=8080 `
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/ticketbooking `
  -e DATABASE_USERNAME=ticketbooking `
  -e DATABASE_PASSWORD=$env:DATABASE_PASSWORD `
  -e JWT_SECRET=$env:JWT_SECRET `
  event-reservation-service
```

## Configuration

Environment variables:

* `PORT`: HTTP port. Defaults to `8181` in the app and `8080` in Docker.
* `DATABASE_URL`: PostgreSQL URL. Accepts `jdbc:postgresql://...`, `postgresql://...`, or `postgres://...`.
* `DATABASE_USERNAME`: PostgreSQL username. Defaults to `ticketbooking`.
* `DATABASE_PASSWORD`: PostgreSQL password. Defaults to empty for local trust-mode compose only.
* `JWT_SECRET`: required HS256 signing secret, at least 32 bytes.
* `SPRING_PROFILES_ACTIVE`: optional Spring profile, for example `docker` or `production`.
* `DB_POOL_MAX_SIZE`: maximum Hikari connections. Defaults to `10`.
* `DB_CONNECTION_TIMEOUT_MS`: database connection acquisition timeout. Defaults to `5000`.

Flyway runs automatically on startup. Hibernate is set to `validate`, so a fresh database becomes usable from the checked-in migrations and the application fails fast if the schema drifts.

Graceful shutdown is enabled with a 30 second shutdown phase. Readiness checks include PostgreSQL; liveness only reports that the process is alive.

JWTs are HS256 tokens with a subject as the user id and a `roles` claim such as `["USER"]` or `["ADMIN"]`. `POST /shows` needs ADMIN. `POST /shows/{id}/reserve` and `POST /reservations/{id}/cancel` need USER. The JSON never accepts a user id.

Swagger UI is available at `/swagger-ui/index.html`; its OpenAPI document is `/v3/api-docs`. Supply JWTs with the bearer-auth control. `GET /health/live` reports whether the process is running, while `GET /health/ready` executes a PostgreSQL query and returns `503` when the database cannot be reached. Actuator health probes remain available under `/actuator/health`.

Prometheus scraping is available at `GET /actuator/prometheus`. The security configuration only permits public access to health, readiness/liveness, Swagger/OpenAPI, `GET /shows/**`, and Prometheus. Other actuator endpoints remain behind authentication or are not exposed by `management.endpoints.web.exposure.include`.

## API

* `POST /shows` creates a show with `name`, `seats`, `price_paise`, and `per_user_limit`.
* `GET /shows/{id}` reads a show.
* `GET /shows/{id}/reconciliation` returns database-derived seat counts and whether they balance.
* `POST /shows/{id}/reserve` accepts `seats` and `idempotency_key`.
* `POST /reservations/{id}/cancel` explicitly cancels the caller's reservation.

Errors contain `timestamp`, `request_id`, `status`, `code`, and `message`. The request filter accepts or generates `X-Request-Id`, returns it in the response, and adds it to logging MDC.

The error contract uses `AUTHENTICATION_REQUIRED` (401), `FORBIDDEN` (403), `SHOW_NOT_FOUND` and `RESERVATION_NOT_FOUND` (404), `SEAT_TAKEN`, `PER_USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, and `INVALID_RESERVATION_STATE` (409), and `VALIDATION_ERROR`, `INVALID_SEAT`, or `INVALID_REQUEST` (400). Unexpected failures produce `INTERNAL_ERROR` without stack traces. Each completed request also emits a JSON log record with request metadata and duration; credentials and request bodies are never logged.

Example calls:

```bash
curl -s http://localhost:8181/health/live
curl -s http://localhost:8181/health/ready
curl -s http://localhost:8181/actuator/prometheus
curl -s http://localhost:8181/shows/{show_id}
curl -s http://localhost:8181/shows/{show_id}/reconciliation
curl -s -X POST http://localhost:8181/shows/{show_id}/reserve \
  -H "Authorization: Bearer $USER_JWT" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: burst-001" \
  -d '{"seats":["A1"],"idempotency_key":"burst-001-A1"}'
```

When running with Docker compose on the default port, replace `8181` with `8080`.

## Deployment

The repository includes `render.yaml` for Render Blueprint deployment. It defines a Docker web service, a managed PostgreSQL database, `/health/ready` as the health check path, generated `JWT_SECRET`, and database environment variables wired from Render Postgres. The app accepts Render's `postgresql://...` database URL format and converts it to the JDBC URL expected by the driver.

Deploy through Render by creating a Blueprint from this repository. Keep secrets in Render environment variables or generated Blueprint values. Do not commit `.env`, passwords, JWT secrets, cloud credentials, or API keys.

For other platforms, use the same container and set `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET`, and optionally `SPRING_PROFILES_ACTIVE`.

## Clean deployment smoke test

From a clean database:

1. Start PostgreSQL and the application with `docker compose up --build`.
2. Wait until `curl -f http://localhost:8080/health/ready` succeeds.
3. Create an ADMIN JWT and a USER JWT signed with the configured `JWT_SECRET`.
4. `POST /shows` with the ADMIN token.
5. `POST /shows/{id}/reserve` with the USER token.
6. `GET /shows/{id}` and confirm the seat is `CONFIRMED`.
7. `POST /reservations/{id}/cancel` with the USER token.
8. Reserve the same seat again with a new `idempotency_key`.

Failure check:

1. Stop PostgreSQL while the application is still running.
2. `GET /health/live` should return `200`.
3. `GET /health/ready` should return `503`.

## Observability

Reservation metrics are low-cardinality. They do not use `user_id`, `reservation_id`, `request_id`, or `seat_number` as labels.

Key metrics:

* `reservation_attempts_total`: every reservation attempt observed by the service, including validation failures on `POST /shows/{id}/reserve`.
* `reservation_outcomes_total{result="confirmed|seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|other_4xx|5xx"}`: exact one-outcome partition for reservation attempts.
* `reservations_confirmed_total`: attempts that created a new confirmed reservation. This answers how many reservations actually succeeded during a burst.
* `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|invalid_request"}`: declined or replayed attempts by bounded reason.
* `reservation_duration_seconds`: reservation latency timer.
* `reservations_cancelled_total`: successful cancellation count.
* `http_requests_total{method,uri,status,outcome}` plus `http_4xx_total` and `http_5xx_total`: HTTP volume and failure families. The `uri` label uses route templates such as `/shows/{id}/reserve`.
* `database_errors_total`: database exceptions seen by the API layer.
* `seats_available`: database-backed gauge of all rows in `show_seat` with `status='AVAILABLE'`.

Show-specific metrics are intentionally aggregate. The service does not attach unbounded show IDs to Prometheus time series. If a deployment has a small, controlled set of active shows and needs per-show metrics, document that maximum cardinality first and prefer exporting only active public shows.

Reconciliation is available per show:

```json
{
  "total": 100,
  "available": 96,
  "held": 0,
  "confirmed": 4,
  "balanced": true
}
```

For each show, `available + held + confirmed = total` should match `GET /shows/{id}`. A `balanced=false` response means the database state or application behavior needs immediate investigation.

Example structured reservation logs:

```json
{"event":"reservation_completed","request_id":"burst-001","user_id":"user-a","show_id":"...","reservation_id":"...","operation":"reserve","result":"CONFIRMED","duration_ms":17}
{"event":"reservation_declined","request_id":"burst-002","user_id":"user-b","show_id":"...","reservation_id":null,"operation":"reserve","result":"SEAT_TAKEN","duration_ms":9}
```

During a burst, watch:

```promql
increase(reservations_confirmed_total[5m])
sum by (result) (increase(reservation_outcomes_total[5m]))
increase(reservations_declined_total{reason="seat_taken"}[5m])
increase(reservations_declined_total{reason="idempotent_replay"}[5m])
increase(http_5xx_total[5m])
histogram_quantile(0.95, rate(reservation_duration_seconds_bucket[5m]))
seats_available
```

## What I would page on at 2am

* Sustained 5xx responses on reservation or cancellation paths.
* Database connectivity failure, rising `database_errors_total`, or readiness returning `DOWN`.
* Any reconciliation invariant violation where `available + held + confirmed != total`.
* Unusually high reservation latency during normal traffic or while the burst test is running.
* Connection pool exhaustion symptoms, including request pileups, readiness failures, or database timeout errors.
* Abnormal reservation failure rate, especially a sudden shift in `seat_taken`, `per_user_limit`, or `idempotency_conflict` that does not match expected demand.

## Database schema

`shows` owns the integer paise price and per-user cap. `show_seat` has one row per physical seat and records its current owner. `reservation` records the immutable price calculation and status; `reservation_seat` is the reservation-to-seat audit mapping. `idempotency_key` stores the request hash and final reservation. `user_show_state` stores the active seat count for a `(show_id,user_id)` pair.

The migration adds foreign keys, enum check constraints, nonnegative price and amount checks, a positive cap check, unique `(show_id, seat_number)`, unique `(show_id, user_id, idempotency_key)`, and unique reservation-seat pairs.

## Concurrency Design

Every reservation is all-or-nothing. A request for `["A12", "A13"]` reserves neither seat if either seat is unavailable and returns `409 SEAT_TAKEN`.

The atomic decision is inside `ReservationService.reserve`'s single Spring transaction. It validates and canonicalizes seat names, then takes PostgreSQL `SELECT ... FOR UPDATE` locks in one global order: first the caller's `(show_id, user_id)` `user_show_state` row, then every requested `show_seat` row in ascending `seat_number` order. Only after all locks are held does it check the canonical request hash/idempotency record, seat state, and per-user count, create the reservation, mark seats `CONFIRMED`, create the audit links, and update the count.

The final seat update is conditional on `status = 'AVAILABLE'` and must update every requested row. Therefore concurrent contenders cannot double-sell: the first transaction holds and changes the locked row; followers see `CONFIRMED` and receive `409 SEAT_TAKEN`. The state-row lock serializes all seat requests for one user/show, so concurrent requests cannot collectively exceed `per_user_limit`.

The database has a unique `(show_id, user_id, idempotency_key)` constraint: idempotency is explicitly scoped to **show + authenticated user + key**. Requests hash the sorted, normalized seat list; `["A12", "A13"]` and `["A13", "A12"]` are equivalent. A repeated key with the same hash returns the original reservation; a different hash returns `409 IDEMPOTENCY_CONFLICT`. Different users may safely use the same key. The unique constraint remains the race backstop even if another caller bypasses normal application flow.

Cancellation is also transactional and follows the same order: user-show state, then (only for cancellation) its reservation row, then seat rows in ascending `seat_number`. It verifies each `CONFIRMED` seat is still owned by that reservation, and only then releases them. It never clears a seat based solely on a reservation id, so a cancellation cannot overwrite a later reservation. Any exception or failed check rolls back the complete transaction and releases PostgreSQL locks; no partial reservation, seat state, count, or idempotency result remains committed. Time-based HELD-seat expiration is intentionally a next-phase mechanism; this version supports explicit cancellation only.
