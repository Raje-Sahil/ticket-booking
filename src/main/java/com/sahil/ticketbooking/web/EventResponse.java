package com.sahil.ticketbooking.web;

import java.time.Instant;

import com.sahil.ticketbooking.model.Event;

public class EventResponse {

    private Long id;
    private String name;
    private String venue;
    private Instant startsAt;

    public static EventResponse from(Event event) {
        EventResponse response = new EventResponse();
        response.id = event.getId();
        response.name = event.getName();
        response.venue = event.getVenue();
        response.startsAt = event.getStartsAt();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }
}
