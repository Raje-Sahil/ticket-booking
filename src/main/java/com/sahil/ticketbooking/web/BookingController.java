package com.sahil.ticketbooking.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sahil.ticketbooking.model.Booking;
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
    public ResponseEntity<BookingResponse> book(@Valid @RequestBody BookingRequest request) {
        Booking booking = bookingService.bookSeat(request.getSeatId(), request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
    }

    // Hold a seat for a few minutes.
    @PostMapping("/holds")
    public ResponseEntity<BookingResponse> hold(@Valid @RequestBody BookingRequest request) {
        Booking booking = bookingService.holdSeat(request.getSeatId(), request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
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
}
