package com.sahil.ticketbooking.service;

import com.sahil.ticketbooking.model.Booking;

/**
 * The result of a hold or booking request: the booking, and whether this was
 * a replay of an earlier request with the same idempotency key.
 */
public class BookingOutcome {

    private final Booking booking;
    private final boolean replayed;

    public BookingOutcome(Booking booking, boolean replayed) {
        this.booking = booking;
        this.replayed = replayed;
    }

    public Booking getBooking() {
        return booking;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
