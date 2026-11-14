package com.sahil.ticketbooking.web;

import java.time.Instant;

import com.sahil.ticketbooking.model.Booking;

public class BookingResponse {

    private Long bookingId;
    private Long seatId;
    private Long userId;
    private String status;
    private Instant holdExpiresAt;
    private Instant createdAt;

    public static BookingResponse from(Booking booking) {
        BookingResponse response = new BookingResponse();
        response.bookingId = booking.getId();
        response.seatId = booking.getSeatId();
        response.userId = booking.getUserId();
        response.status = booking.getStatus().name();
        response.holdExpiresAt = booking.getHoldExpiresAt();
        response.createdAt = booking.getCreatedAt();
        return response;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
