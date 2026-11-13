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

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.orm.jdbc.error=OFF"
})
class BookingBenchmarkTest {

    private static final int THREADS = 200;
    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURED_ROUNDS = 5;
    private static final int[] SEAT_COUNTS = {1, 10, 200};

    enum Strategy {
        NAIVE, PESSIMISTIC, OPTIMISTIC
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Outcome of one run (one round of 200 threads). */
    static class RunResult {
        boolean finished;
        long elapsedMillis;
        int success;
        int rejectedByCheck;
        int rejectedByVersionConflict;
        int rejectedByDatabase;
        int otherErrors;
        int activeBookings;
    }

    @Test
    void benchmark() throws Exception {
        // ---- One-time setup: 200 users and 1 event, reused by every run ----
        jdbcTemplate.execute("TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE");
        for (int i = 1; i <= THREADS; i++) {
            jdbcTemplate.update("INSERT INTO users (name, email) VALUES (?, ?)",
                    "User" + i, "user" + i + "@test.com");
        }
        jdbcTemplate.update("INSERT INTO events (name, venue, starts_at) VALUES ('Benchmark Show', 'Hall', now() + interval '7 days')");
        Long eventId = jdbcTemplate.queryForObject("SELECT id FROM events LIMIT 1", Long.class);
        List<Long> userIds = jdbcTemplate.queryForList("SELECT id FROM users ORDER BY id", Long.class);

        StringBuilder report = new StringBuilder();
        report.append("\n================ BENCHMARK (").append(THREADS).append(" threads, ")
                .append(MEASURED_ROUNDS).append(" measured rounds, averages) ================\n");
        report.append(String.format("%-6s | %-12s | %9s | %7s | %7s | %9s | %7s%n",
                "seats", "strategy", "avg ms", "success", "check", "version", "DB-err"));
        report.append("-------+--------------+-----------+---------+---------+-----------+--------\n");

        for (int s = 0; s < SEAT_COUNTS.length; s++) {
            int seatCount = SEAT_COUNTS[s];
            for (Strategy strategy : Strategy.values()) {

                for (int w = 0; w < WARMUP_ROUNDS; w++) {
                    runOnce(strategy, seatCount, eventId, userIds);
                }

                long totalMillis = 0;
                int totalSuccess = 0;
                int totalCheck = 0;
                int totalVersion = 0;
                int totalDb = 0;

                for (int r = 0; r < MEASURED_ROUNDS; r++) {
                    RunResult res = runOnce(strategy, seatCount, eventId, userIds);

                    // Correctness checks on every measured run
                    assertTrue(res.finished, "threads did not finish in time");
                    assertEquals(0, res.otherErrors);
                    assertEquals(seatCount, res.success);
                    assertEquals(seatCount, res.activeBookings);
                    if (strategy != Strategy.NAIVE) {
                        assertEquals(0, res.rejectedByDatabase);
                    }

                    totalMillis += res.elapsedMillis;
                    totalSuccess += res.success;
                    totalCheck += res.rejectedByCheck;
                    totalVersion += res.rejectedByVersionConflict;
                    totalDb += res.rejectedByDatabase;
                }

                report.append(String.format("%-6d | %-12s | %9d | %7d | %7d | %9d | %7d%n",
                        seatCount, strategy,
                        totalMillis / MEASURED_ROUNDS,
                        totalSuccess / MEASURED_ROUNDS,
                        totalCheck / MEASURED_ROUNDS,
                        totalVersion / MEASURED_ROUNDS,
                        totalDb / MEASURED_ROUNDS));
            }
            report.append("-------+--------------+-----------+---------+---------+-----------+--------\n");
        }

        report.append("Columns: success = bookings made, check = rejected by app-level check,\n");
        report.append("         version = rejected by version conflict, DB-err = stopped only by the DB index.\n");
        System.out.println(report.toString());
    }

    private RunResult runOnce(final Strategy strategy, int seatCount, Long eventId, List<Long> userIds)
            throws Exception {

        // Fresh seats and no bookings for every run
        jdbcTemplate.execute("TRUNCATE bookings, seats RESTART IDENTITY");
        for (int i = 1; i <= seatCount; i++) {
            jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, ?)", eventId, "S" + i);
        }
        final List<Long> seatIds = jdbcTemplate.queryForList("SELECT id FROM seats ORDER BY id", Long.class);

        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger rejectedByCheck = new AtomicInteger();
        final AtomicInteger rejectedByVersionConflict = new AtomicInteger();
        final AtomicInteger rejectedByDatabase = new AtomicInteger();
        final AtomicInteger otherErrors = new AtomicInteger();

        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            // Thread i goes for seat (i % seatCount), so seats are shared evenly
            final Long seatId = seatIds.get(i % seatCount);
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
                        rejectedByCheck.incrementAndGet();
                    } catch (ConcurrentBookingException e) {
                        rejectedByVersionConflict.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
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

        ready.await();
        long startNanos = System.nanoTime();
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startNanos;
        pool.shutdown();

        RunResult result = new RunResult();
        result.finished = finished;
        result.elapsedMillis = elapsedNanos / 1000000L;
        result.success = success.get();
        result.rejectedByCheck = rejectedByCheck.get();
        result.rejectedByVersionConflict = rejectedByVersionConflict.get();
        result.rejectedByDatabase = rejectedByDatabase.get();
        result.otherErrors = otherErrors.get();
        result.activeBookings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE status IN ('HELD', 'CONFIRMED')",
                Integer.class).intValue();
        return result;
    }
}
