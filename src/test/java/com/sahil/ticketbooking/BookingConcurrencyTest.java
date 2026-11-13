package com.sahil.ticketbooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahil.ticketbooking.service.BookingService;
import com.sahil.ticketbooking.service.ConcurrentBookingException;
import com.sahil.ticketbooking.service.SeatAlreadyBookedException;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class BookingConcurrencyTest {

    private static final int THREADS = 200;

    enum Strategy {
        NAIVE, PESSIMISTIC, OPTIMISTIC
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Holds the outcome of one race. */
    static class RaceResult {
        boolean finished;
        int success;
        int rejectedByCheck;
        int rejectedByVersionConflict;
        int rejectedByDatabase;
        int otherErrors;
        int activeBookings;

        int total() {
            return success + rejectedByCheck + rejectedByVersionConflict + rejectedByDatabase;
        }
    }

    @Test
    void naiveVersion() throws Exception {
        RaceResult r = runRace(Strategy.NAIVE);

        assertTrue(r.finished, "threads did not finish in time");
        assertEquals(0, r.otherErrors);
        assertEquals(1, r.success);
        assertEquals(1, r.activeBookings);
        assertEquals(THREADS, r.total());
    }

    @Test
    void pessimisticLockVersion() throws Exception {
        RaceResult r = runRace(Strategy.PESSIMISTIC);

        assertTrue(r.finished, "threads did not finish in time");
        assertEquals(0, r.otherErrors);
        assertEquals(1, r.success);
        assertEquals(THREADS - 1, r.rejectedByCheck);
        assertEquals(0, r.rejectedByDatabase);
        assertEquals(1, r.activeBookings);
    }

    @Test
    void optimisticLockVersion() throws Exception {
        RaceResult r = runRace(Strategy.OPTIMISTIC);

        assertTrue(r.finished, "threads did not finish in time");
        assertEquals(0, r.otherErrors);
        assertEquals(1, r.success);
        assertEquals(0, r.rejectedByDatabase);
        assertEquals(1, r.activeBookings);
        assertEquals(THREADS, r.total());
    }

    private RaceResult runRace(final Strategy strategy) throws Exception {
        // ---- Setup: clean tables, 200 users, 1 event, 1 seat ----
        jdbcTemplate.execute("TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE");
        for (int i = 1; i <= THREADS; i++) {
            jdbcTemplate.update("INSERT INTO users (name, email) VALUES (?, ?)",
                    "User" + i, "user" + i + "@test.com");
        }
        jdbcTemplate.update("INSERT INTO events (name, venue, starts_at) VALUES ('Test Show', 'Hall', now() + interval '7 days')");
        Long eventId = jdbcTemplate.queryForObject("SELECT id FROM events LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, 'A1')", eventId);

        final Long seatId = jdbcTemplate.queryForObject("SELECT id FROM seats WHERE seat_label = 'A1'", Long.class);
        List<Long> userIds = jdbcTemplate.queryForList("SELECT id FROM users ORDER BY id", Long.class);

        // ---- Counters for the possible outcomes ----
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger rejectedByCheck = new AtomicInteger();
        final AtomicInteger rejectedByVersionConflict = new AtomicInteger();
        final AtomicInteger rejectedByDatabase = new AtomicInteger();
        final AtomicInteger otherErrors = new AtomicInteger();

        // ---- Latches so all threads start at the same moment ----
        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            final Long userId = userIds.get(i);
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    ready.countDown();
                    try {
                        start.await();
                        if (strategy == Strategy.PESSIMISTIC) {
                            bookingService.bookSeatPessimistic(seatId, userId);
                        } else if (strategy == Strategy.OPTIMISTIC) {
                            bookingService.bookSeatOptimistic(seatId, userId);
                        } else {
                            bookingService.bookSeatNaive(seatId, userId);
                        }
                        success.incrementAndGet();
                    } catch (SeatAlreadyBookedException e) {
                        // The app-level check saw the seat as taken.
                        rejectedByCheck.incrementAndGet();
                    } catch (ConcurrentBookingException e) {
                        // The seat's version changed under us: someone else won the race.
                        rejectedByVersionConflict.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        // The check said "free", but the database index stopped us.
                        rejectedByDatabase.incrementAndGet();
                    } catch (Exception e) {
                        otherErrors.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        ready.await();       // wait until every thread is waiting at the gate
        start.countDown();   // open the gate: all threads go at once
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        RaceResult result = new RaceResult();
        result.finished = finished;
        result.success = success.get();
        result.rejectedByCheck = rejectedByCheck.get();
        result.rejectedByVersionConflict = rejectedByVersionConflict.get();
        result.rejectedByDatabase = rejectedByDatabase.get();
        result.otherErrors = otherErrors.get();
        result.activeBookings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status IN ('HELD', 'CONFIRMED')",
                Integer.class, seatId).intValue();

        System.out.println("=========== RESULT: " + strategy + " ===========");
        System.out.println("Successful bookings          : " + result.success);
        System.out.println("Rejected by app-level check  : " + result.rejectedByCheck);
        System.out.println("Rejected by version conflict : " + result.rejectedByVersionConflict);
        System.out.println("Passed check, stopped by DB  : " + result.rejectedByDatabase);
        System.out.println("Other errors                 : " + result.otherErrors);
        System.out.println("Active bookings in DB        : " + result.activeBookings);
        System.out.println("=======================================================");

        return result;
    }
}
