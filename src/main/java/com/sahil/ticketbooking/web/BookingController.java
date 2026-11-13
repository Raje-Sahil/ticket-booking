package com.sahil.ticketbooking.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.service.BookingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> book(@Valid @RequestBody BookingRequest request) {
        Booking booking = bookingService.bookSeat(request.getSeatId(), request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
    }
}
