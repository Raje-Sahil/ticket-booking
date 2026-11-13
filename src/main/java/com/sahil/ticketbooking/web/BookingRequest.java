package com.sahil.ticketbooking.web;

import jakarta.validation.constraints.NotNull;

public class BookingRequest {

    @NotNull
    private Long seatId;

    @NotNull
    private Long userId;

    public Long getSeatId() {
        return seatId;
    }

    public void setSeatId(Long seatId) {
        this.seatId = seatId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
