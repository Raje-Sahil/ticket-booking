package com.sahil.ticketbooking.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.sahil.ticketbooking.service.BookingOutcome;
import com.sahil.ticketbooking.service.BookingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // Book a seat immediately.
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> book(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingOutcome outcome = bookingService.bookSeat(
                request.getSeatId(), request.getUserId(), checkKey(idempotencyKey));
        return toResponse(outcome);
    }

    // Hold a seat for a few minutes.
    @PostMapping("/holds")
    public ResponseEntity<BookingResponse> hold(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BookingOutcome outcome = bookingService.holdSeat(
                request.getSeatId(), request.getUserId(), checkKey(idempotencyKey));
        return toResponse(outcome);
    }

    // Turn a hold into a confirmed booking.
    @PostMapping("/bookings/{bookingId}/confirm")
    public BookingResponse confirm(@PathVariable Long bookingId, @RequestParam Long userId) {
        return BookingResponse.from(bookingService.confirmBooking(bookingId, userId));
    }

    // Cancel a hold or a booking.
    @PostMapping("/bookings/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable Long bookingId, @RequestParam Long userId) {
        return BookingResponse.from(bookingService.cancelBooking(bookingId, userId));
    }

    // 201 for a new booking; 200 plus a header when an earlier request is being replayed.
    private ResponseEntity<BookingResponse> toResponse(BookingOutcome outcome) {
        BookingResponse body = BookingResponse.from(outcome.getBooking());
        if (outcome.isReplayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // The key is optional, but if present it must be 1 to 100 characters (the column size).
    private String checkKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.trim().isEmpty() || key.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be between 1 and 100 characters");
        }
        return key;
    }
}
