package com.sahil.ticketbooking.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahil.ticketbooking.model.BookingStatus;
import com.sahil.ticketbooking.model.Event;
import com.sahil.ticketbooking.model.Seat;
import com.sahil.ticketbooking.repository.BookingRepository;
import com.sahil.ticketbooking.repository.EventRepository;
import com.sahil.ticketbooking.repository.SeatRepository;
import com.sahil.ticketbooking.web.EventResponse;
import com.sahil.ticketbooking.web.SeatResponse;

@Service
public class EventQueryService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    public EventQueryService(EventRepository eventRepository,
                             SeatRepository seatRepository,
                             BookingRepository bookingRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listEvents() {
        List<EventResponse> result = new ArrayList<EventResponse>();
        for (Event event : eventRepository.findAll()) {
            result.add(EventResponse.from(event));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> listSeats(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found: " + eventId);
        }

        List<Seat> seats = seatRepository.findByEventIdOrderById(eventId);
        List<Long> takenIds = bookingRepository.findActiveSeatIdsForEvent(
                eventId, Arrays.asList(BookingStatus.HELD, BookingStatus.CONFIRMED));
        Set<Long> taken = new HashSet<Long>(takenIds);

        List<SeatResponse> result = new ArrayList<SeatResponse>();
        for (Seat seat : seats) {
            boolean available = !taken.contains(seat.getId());
            result.add(new SeatResponse(seat.getId(), seat.getSeatLabel(), available));
        }
        return result;
    }
}
