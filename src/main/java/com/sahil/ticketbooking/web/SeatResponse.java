package com.sahil.ticketbooking.web;

public class SeatResponse {

    private final Long id;
    private final String label;
    private final boolean available;

    public SeatResponse(Long id, String label, boolean available) {
        this.id = id;
        this.label = label;
        this.available = available;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public boolean isAvailable() {
        return available;
    }
}
