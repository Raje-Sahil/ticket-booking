package com.sahil.ticketbooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sends real HTTP requests to the running application and checks status codes and bodies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApiTest {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Long eventId;
    private Long alice;
    private Long bob;
    private List<Long> seatIds;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com'), ('Bob', 'bob@test.com')");
        jdbcTemplate.update("INSERT INTO events (name, venue, starts_at) VALUES ('Demo Concert', 'Main Hall', now() + interval '30 days')");
        eventId = jdbcTemplate.queryForObject("SELECT id FROM events LIMIT 1", Long.class);
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update("INSERT INTO seats (event_id, seat_label) VALUES (?, ?)", eventId, "A" + i);
        }
        seatIds = jdbcTemplate.queryForList("SELECT id FROM seats ORDER BY id", Long.class);
        alice = jdbcTemplate.queryForObject("SELECT id FROM users WHERE name = 'Alice'", Long.class);
        bob = jdbcTemplate.queryForObject("SELECT id FROM users WHERE name = 'Bob'", Long.class);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void bookingAFreeSeatReturns201AndASecondBookingReturns409() throws Exception {
        Long seat = seatIds.get(0);

        HttpResponse<String> first = post("/api/bookings", bookingJson(seat, alice), null);
        assertEquals(201, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"status\":\"CONFIRMED\""), first.body());

        HttpResponse<String> second = post("/api/bookings", bookingJson(seat, bob), null);
        assertEquals(409, second.statusCode(), second.body());
    }

    @Test
    void unknownSeatOrUserReturns404AndAMissingFieldReturns400() throws Exception {
        HttpResponse<String> unknownSeat = post("/api/bookings", bookingJson(999999L, alice), null);
        assertEquals(404, unknownSeat.statusCode(), unknownSeat.body());

        HttpResponse<String> unknownUser = post("/api/bookings", bookingJson(seatIds.get(0), 999999L), null);
        assertEquals(404, unknownUser.statusCode(), unknownUser.body());

        HttpResponse<String> missingSeat = post("/api/bookings", "{\"userId\":" + alice + "}", null);
        assertEquals(400, missingSeat.statusCode(), missingSeat.body());
    }

    @Test
    void holdConfirmAndCancelLifecycle() throws Exception {
        Long seat = seatIds.get(1);

        // Alice holds the seat
        HttpResponse<String> hold = post("/api/holds", bookingJson(seat, alice), null);
        assertEquals(201, hold.statusCode(), hold.body());
        assertTrue(hold.body().contains("\"status\":\"HELD\""), hold.body());
        long bookingId = extractLong(hold.body(), "bookingId");
        assertTrue(!seatIsAvailable(seat), "a held seat should not be available");

        // Bob cannot hold it, and cannot confirm Alice's booking
        HttpResponse<String> bobHold = post("/api/holds", bookingJson(seat, bob), null);
        assertEquals(409, bobHold.statusCode(), bobHold.body());
        HttpResponse<String> bobConfirm = post("/api/bookings/" + bookingId + "/confirm?userId=" + bob, null, null);
        assertEquals(404, bobConfirm.statusCode(), bobConfirm.body());

        // Alice confirms, and confirming again is harmless
        HttpResponse<String> confirm = post("/api/bookings/" + bookingId + "/confirm?userId=" + alice, null, null);
        assertEquals(200, confirm.statusCode(), confirm.body());
        assertTrue(confirm.body().contains("\"status\":\"CONFIRMED\""), confirm.body());
        HttpResponse<String> confirmAgain = post("/api/bookings/" + bookingId + "/confirm?userId=" + alice, null, null);
        assertEquals(200, confirmAgain.statusCode(), confirmAgain.body());

        // Alice cancels: the seat is free again and Bob can book it
        HttpResponse<String> cancel = post("/api/bookings/" + bookingId + "/cancel?userId=" + alice, null, null);
        assertEquals(200, cancel.statusCode(), cancel.body());
        assertTrue(cancel.body().contains("\"status\":\"CANCELLED\""), cancel.body());
        assertTrue(seatIsAvailable(seat), "a cancelled booking should free the seat");

        HttpResponse<String> bobBooks = post("/api/bookings", bookingJson(seat, bob), null);
        assertEquals(201, bobBooks.statusCode(), bobBooks.body());
    }

    @Test
    void anExpiredHoldCannotBeConfirmedAndFreesTheSeat() throws Exception {
        Long seat = seatIds.get(2);

        HttpResponse<String> hold = post("/api/holds", bookingJson(seat, alice), null);
        assertEquals(201, hold.statusCode(), hold.body());
        long bookingId = extractLong(hold.body(), "bookingId");

        // Age the hold so it has already run out
        jdbcTemplate.update("UPDATE bookings SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", bookingId);

        assertTrue(seatIsAvailable(seat), "a seat with an expired hold should show as available");

        HttpResponse<String> confirm = post("/api/bookings/" + bookingId + "/confirm?userId=" + alice, null, null);
        assertEquals(409, confirm.statusCode(), confirm.body());

        HttpResponse<String> bobHold = post("/api/holds", bookingJson(seat, bob), null);
        assertEquals(201, bobHold.statusCode(), bobHold.body());
    }

    @Test
    void idempotencyKeyReplayReturnsTheSameBooking() throws Exception {
        Long seat = seatIds.get(3);
        Long otherSeat = seatIds.get(4);

        HttpResponse<String> first = post("/api/holds", bookingJson(seat, alice), "key-1");
        assertEquals(201, first.statusCode(), first.body());
        long bookingId = extractLong(first.body(), "bookingId");

        HttpResponse<String> replay = post("/api/holds", bookingJson(seat, alice), "key-1");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotent-Replayed").orElse(""));
        assertEquals(bookingId, extractLong(replay.body(), "bookingId"));

        HttpResponse<String> differentSeat = post("/api/holds", bookingJson(otherSeat, alice), "key-1");
        assertEquals(422, differentSeat.statusCode(), differentSeat.body());

        String tooLongKey = "x".repeat(101);
        HttpResponse<String> badKey = post("/api/holds", bookingJson(otherSeat, alice), tooLongKey);
        assertEquals(400, badKey.statusCode(), badKey.body());

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class);
        assertEquals(1, rows.intValue());
    }

    @Test
    void eventAndSeatListing() throws Exception {
        HttpResponse<String> events = get("/api/events");
        assertEquals(200, events.statusCode(), events.body());
        assertTrue(events.body().contains("Demo Concert"), events.body());

        HttpResponse<String> seats = get("/api/events/" + eventId + "/seats");
        assertEquals(200, seats.statusCode(), seats.body());
        assertEquals(5, countOccurrences(seats.body(), "\"label\""));

        HttpResponse<String> unknownEvent = get("/api/events/999999/seats");
        assertEquals(404, unknownEvent.statusCode(), unknownEvent.body());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    private String bookingJson(Long seatId, Long userId) {
        return "{\"seatId\":" + seatId + ",\"userId\":" + userId + "}";
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest.BodyPublisher body = (json == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(body);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long extractLong(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), "field " + field + " not found in: " + body);
        return Long.parseLong(matcher.group(1));
    }

    private boolean seatIsAvailable(Long seatId) throws Exception {
        HttpResponse<String> response = get("/api/events/" + eventId + "/seats");
        assertEquals(200, response.statusCode(), response.body());
        Matcher matcher = Pattern.compile("\\{[^{}]*\"id\":" + seatId + "(?![0-9])[^{}]*\\}").matcher(response.body());
        assertTrue(matcher.find(), "seat " + seatId + " not found in: " + response.body());
        return matcher.group().contains("\"available\":true");
    }

    private int countOccurrences(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }
}
