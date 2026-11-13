# Concurrency-safe ticket booking

A Java / Spring Boot / PostgreSQL project that answers one question:
**what happens when hundreds of users try to book the same seat at the same moment?**

It implements three booking strategies, proves the race condition exists, and measures how each strategy behaves under contention.

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
   | Naive | Check, then insert, no lock | Some pass the check and are only stopped by the database index (a raw DB exception) |
   | Pessimistic | `SELECT ... FOR UPDATE` on the seat row, then check and insert | Queue on the lock, then get a clean "seat already booked" |
   | Optimistic | Read the seat version, check, bump the version only if unchanged (`UPDATE ... WHERE version = ?`), then insert | Clean rejection, or a version-conflict exception |

## Results

Test setup: 200 threads released at the same instant, local PostgreSQL 18, 50-connection pool, 1 warm-up round and 5 measured rounds per row (averages). The numbers are from one laptop and indicative only. Every run asserted that each seat ended with exactly one active booking.

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

- **Naive is only correct because of the database index.** Each run, between 8 and 16 threads on average passed the check and were stopped only by the index. Without it they would be double bookings.
- **One hot seat:** pessimistic was slowest, because transactions queue on a single row lock, but it had no wasted work and no database errors. Optimistic was about as fast as naive with no database errors.
- **No contention:** the three strategies were within about 30 ms of each other, which is within the noise of this setup.
- Limits: a single machine, a 50-connection pool (which caps real concurrency), and only 5 measured rounds.

## Run it

Requirements: Java 17+, PostgreSQL, a database named `ticketdb`.

```powershell
$env:DB_PASSWORD = 'your_postgres_password'
.\mvnw.cmd test -Dtest=BookingConcurrencyTest
.\mvnw.cmd test -Dtest=BookingBenchmarkTest
```

Both tests truncate the tables in `ticketdb`, so don't point them at data you care about. The schema is created by Flyway (`src/main/resources/db/migration`).

## Status

Done: schema, three booking strategies, concurrency test, benchmark.
Planned: REST API, seat holds that expire, idempotency keys.
