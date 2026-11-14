package com.sahil.ticketbooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahil.ticketbooking.service.BookingOutcome;
import com.sahil.ticketbooking.service.BookingService;
import com.sahil.ticketbooking.service.IdempotencyKeyMismatchException;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class IdempotencyTest {

    private static final int THREADS = 50;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fiftyIdenticalRequestsCreateExactlyOneBooking() throws Exception {
        // ---- Setup: 1 user, 1 event, 1 seat ----
        jdbcTemplate.execute("TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (name, email) VALUES ('A', 'a@test.com')");
        jdbcTemplate.update("INSERT INTO events (name, venue, starts_at) VALUES ('Test Show', 'Hall', now() + interval '7 days')");
        Long eventId = jdbcTemplate.queryForObject("SELECT id FROM events LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, 'A1')", eventId);
        final Long seatId = jdbcTemplate.queryForObject("SELECT id FROM seats LIMIT 1", Long.class);
        final Long userId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", Long.class);

        final String key = "test-key-123";
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger replayed = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final Set<Long> bookingIds = ConcurrentHashMap.newKeySet();

        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        start.await();
                        BookingOutcome outcome = bookingService.holdSeat(seatId, userId, key);
                        bookingIds.add(outcome.getBooking().getId());
                        if (outcome.isReplayed()) {
                            replayed.incrementAndGet();
                        } else {
                            created.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        ready.await();
        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class);

        System.out.println("=========== IDEMPOTENCY RESULT ===========");
        System.out.println("Created a new booking        : " + created.get());
        System.out.println("Replayed an earlier booking  : " + replayed.get());
        System.out.println("Errors                       : " + errors.get());
        System.out.println("Distinct booking ids returned: " + bookingIds.size());
        System.out.println("Rows in bookings table       : " + rows);
        System.out.println("==========================================");

        assertTrue(finished, "threads did not finish in time");
        assertEquals(0, errors.get());
        assertEquals(1, created.get());
        assertEquals(THREADS - 1, replayed.get());
        assertEquals(1, bookingIds.size());
        assertEquals(1, rows.intValue());
    }

    @Test
    void sameKeyForADifferentSeatIsRejected() throws Exception {
        jdbcTemplate.execute("TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (name, email) VALUES ('A', 'a@test.com')");
        jdbcTemplate.update("INSERT INTO events (name, venue, starts_at) VALUES ('Test Show', 'Hall', now() + interval '7 days')");
        Long eventId = jdbcTemplate.queryForObject("SELECT id FROM events LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, 'A1')", eventId);
        jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, 'A2')", eventId);
        Long seat1 = jdbcTemplate.queryForObject("SELECT id FROM seats WHERE seat_label = 'A1'", Long.class);
        Long seat2 = jdbcTemplate.queryForObject("SELECT id FROM seats WHERE seat_label = 'A2'", Long.class);
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", Long.class);

        bookingService.holdSeat(seat1, userId, "key-abc");

        boolean rejected = false;
        try {
            bookingService.holdSeat(seat2, userId, "key-abc");
        } catch (IdempotencyKeyMismatchException e) {
            rejected = true;
        }

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class);
        assertTrue(rejected, "reusing a key for a different seat should be rejected");
        assertEquals(1, rows.intValue());
    }
}
