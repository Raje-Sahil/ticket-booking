package com.sahil.ticketbooking.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sahil.ticketbooking.service.EventQueryService;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventQueryService eventQueryService;

    public EventController(EventQueryService eventQueryService) {
        this.eventQueryService = eventQueryService;
    }

    @GetMapping
    public List<EventResponse> listEvents() {
        return eventQueryService.listEvents();
    }

    @GetMapping("/{eventId}/seats")
    public List<SeatResponse> listSeats(@PathVariable Long eventId) {
        return eventQueryService.listSeats(eventId);
    }
}
