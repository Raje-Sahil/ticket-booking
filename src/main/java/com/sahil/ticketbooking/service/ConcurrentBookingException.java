package com.sahil.ticketbooking.service;

public class ConcurrentBookingException extends RuntimeException {

    public ConcurrentBookingException(Long seatId) {
        super("Seat " + seatId + " was modified by another booking at the same time");
    }
}
