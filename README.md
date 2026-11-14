# Concurrency-safe ticket booking

[![CI](https://github.com/Raje-Sahil/ticket-booking/actions/workflows/ci.yml/badge.svg)](https://github.com/Raje-Sahil/ticket-booking/actions/workflows/ci.yml)

A Java / Spring Boot / PostgreSQL service that answers one question:
**what happens when hundreds of users try to book the same seat at the same moment?**

It implements three booking strategies, shows that the race condition is real, measures each strategy under contention, and exposes the safest one through a REST API with seat holds, expiry and idempotency keys.

## The problem

A booking method that first checks "is this seat free?" and then inserts a booking has a gap between the check and the insert. Two threads can both see the seat as free, and both try to book it.

## Defence in depth

1. **Database constraint.** A partial unique index allows at most one active (HELD or CONFIRMED) booking per seat, no matter what the application does:

   ```sql
   CREATE UNIQUE INDEX uq_one_active_booking_per_seat
       ON bookings (seat_id)
       WHERE status IN ('HELD', 'CONFIRMED');
   ```

2. **Application strategies**, compared in `BookingService`:

   | Strategy | How it works | How the losers fail |
   |---|---|---|
   | Naive | Check, then insert, no lock | Some pass the check and are stopped only by the database index (a raw DB exception) |
   | Pessimistic | `SELECT ... FOR UPDATE` on the seat row, then check and insert | Queue on the lock, then get a clean "seat already booked" |
   | Optimistic | Read the seat version, check, bump the version only if unchanged (`UPDATE ... WHERE version = ?`), then insert | Clean rejection, or a version-conflict exception |

The REST API uses the **pessimistic** strategy. The other two are kept for comparison in the tests and the benchmark.

## Benchmark results

Setup: 200 threads released at the same instant, local PostgreSQL 18, 50-connection pool, 1 warm-up round and 5 measured rounds per row (averages). The numbers come from one laptop and are indicative only. Every measured run asserted that each seat ended with exactly one active booking.

| Seats | Strategy | Avg time (ms) | Rejected by check | Version conflicts | Stopped only by DB |
|---|---|---|---|---|---|
| 1 | Naive | 109 | 182 | 0 | 16 |
| 1 | Pessimistic | 286 | 199 | 0 | 0 |
| 1 | Optimistic | 101 | 187 | 12 | 0 |
| 10 | Naive | 66 | 182 | 0 | 8 |
| 10 | Pessimistic | 88 | 190 | 0 | 0 |
| 10 | Optimistic | 84 | 183 | 7 | 0 |
| 200 | Naive | 100 | 0 | 0 | 0 |
| 200 | Pessimistic | 117 | 0 | 0 | 0 |
| 200 | Optimistic | 128 | 0 | 0 | 0 |

What the results show:

- **Naive is correct only because of the database index.** Each run, a handful to a few dozen threads passed the check and were stopped only by the index. Without it they would be double bookings.
- **One hot seat:** pessimistic was the slowest, because transactions queue on a single row lock, but it had no wasted work and no database errors. Optimistic was about as fast as naive, with no database errors.
- **No contention:** the three strategies were within about 30 ms of each other, which is within the noise of this setup.
- Limits: a single machine, a 50-connection pool (which caps real concurrency), and only 5 measured rounds.

## REST API

| Method | Path | What it does | Success | Errors |
|---|---|---|---|---|
| GET | `/api/events` | List events | 200 | |
| GET | `/api/events/{eventId}/seats` | List seats with `available` true/false | 200 | 404 unknown event |
| POST | `/api/bookings` | Book a seat immediately (status CONFIRMED) | 201 | 400 invalid body, 404 unknown seat or user, 409 seat taken, 422 idempotency key reused |
| POST | `/api/holds` | Hold a seat for 5 minutes (status HELD) | 201 | same as above |
| POST | `/api/bookings/{id}/confirm?userId=` | Turn a live hold into a confirmed booking | 200 | 404 unknown or not yours, 409 hold expired or wrong state |
| POST | `/api/bookings/{id}/cancel?userId=` | Cancel a hold or booking | 200 | 404 unknown or not yours |

Request body for `/api/bookings` and `/api/holds`:

```json
{ "seatId": 7, "userId": 1 }
```

Errors use the RFC 7807 `application/problem+json` format. Confirming or cancelling twice is harmless and returns the current booking.

## Holds and expiry

A hold lasts 5 minutes (`booking.hold-minutes`). An expired hold must not block a seat, so:

- **On demand:** whenever someone holds or books a seat, that seat's stale holds are first marked EXPIRED, inside the same locked transaction. The seat listing also ignores expired holds. Correctness never depends on a background job running on time.
- **Housekeeping:** a scheduled job (`HoldExpiryJob`, every 30 seconds by default, `booking.expiry-job-interval-ms`) marks all stale holds EXPIRED so the table does not fill with dead rows.
- Confirming an expired hold returns 409 "The hold has expired".

## Idempotency keys

`POST /api/holds` and `POST /api/bookings` accept an optional `Idempotency-Key` header (1 to 100 characters). A retry of the same request, such as a double click or a network retry, then returns the original booking instead of failing:

| Request | Result |
|---|---|
| First request with a key | 201, booking created |
| Same key, same seat and user | 200 with the same booking and an `Idempotent-Replayed: true` header |
| Same key, different seat or user | 422 |
| No key | Works as before |

The key is checked after the seat lock is taken, so concurrent retries line up behind the lock and each one finds the first booking. The `UNIQUE` constraint on `idempotency_key` is the last line of defence. Test result: **50 identical requests released at the same instant created 1 booking; the other 49 returned that same booking** (1 row in the table).

Example (on Windows PowerShell use `curl.exe` and put the JSON in a file):

## Tests

| Test class | What it checks |
|---|---|
| `BookingConcurrencyTest` | 200 threads, 1 seat, for each of the three strategies: exactly 1 booking; pessimistic and optimistic have zero database-level rejections |
| `BookingBenchmarkTest` | The benchmark above, plus a correctness check on every run |
| `IdempotencyTest` | 50 concurrent identical requests create exactly 1 booking; a key reused for a different seat is rejected |
| `BookingApiTest` | Real HTTP requests against the running app: booking, 404 and 400 errors, hold/confirm/cancel, expired holds, idempotency replay, event and seat listing |

## Run it

Requirements: Java 17 or newer, PostgreSQL (developed against 18), and a database named `ticketdb`. The schema is created by Flyway (`src/main/resources/db/migration`).

```powershell
psql -U postgres -c "CREATE DATABASE ticketdb;"
$env:DB_PASSWORD = 'your_postgres_password'
.\mvnw.cmd spring-boot:run
```

In a second window, load demo data (2 users, 1 event, 10 seats) and try the API:

```powershell
$env:PGPASSWORD = 'your_postgres_password'
psql -U postgres -d ticketdb -f scripts\seed.sql
Invoke-RestMethod http://localhost:8080/api/events/1/seats | Format-Table
```

Run the tests:

```powershell
.\mvnw.cmd test
```

Tests run against a separate database. Create it once with `psql -U postgres -c "CREATE DATABASE ticketdb_test;"` (Flyway creates the schema on the first run). The tests truncate the tables in `ticketdb_test` and never touch `ticketdb`; the seed script truncates the tables in `ticketdb`.

## Project layout

## Limits and design notes

- **No authentication.** The caller passes `userId`; user management is out of scope.
- **Idempotency keys are global,** not scoped per user. A key used by one user and then by another gets a 422.
- Tested against a single application instance and one local PostgreSQL. The benchmark numbers are from one laptop.
- The optimistic strategy is implemented and tested but not used by the API; the pessimistic one is, because on a hot seat it gives clean rejections with no wasted work.




## License

MIT. See the LICENSE file.
