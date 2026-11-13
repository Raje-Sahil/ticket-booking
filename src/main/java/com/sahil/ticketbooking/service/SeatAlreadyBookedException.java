package com.sahil.ticketbooking.service;

public class SeatAlreadyBookedException extends RuntimeException {

    public SeatAlreadyBookedException(Long seatId) {
        super("Seat " + seatId + " is already booked");
    }
}
