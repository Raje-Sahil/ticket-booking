package com.sahil.ticketbooking.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.model.BookingStatus;
import com.sahil.ticketbooking.model.Seat;
import com.sahil.ticketbooking.repository.BookingRepository;
import com.sahil.ticketbooking.repository.SeatRepository;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;

    public BookingService(BookingRepository bookingRepository, SeatRepository seatRepository) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * NAIVE version: check first, then insert, with no lock.
     * Between the check and the insert, another thread can do the same thing.
     */
    @Transactional
    public Booking bookSeatNaive(Long seatId, Long userId) {
        ensureSeatIsFree(seatId);
        return insertBooking(seatId, userId);
    }

    /**
     * PESSIMISTIC version: lock the seat row first, then check and insert.
     * Only one transaction at a time can hold the lock for a given seat,
     * so the check can no longer be fooled by a concurrent booking.
     */
    @Transactional
    public Booking bookSeatPessimistic(Long seatId, Long userId) {
        Optional<Seat> seat = seatRepository.findByIdForUpdate(seatId);
        if (!seat.isPresent()) {
            throw new IllegalArgumentException("Seat not found: " + seatId);
        }
        ensureSeatIsFree(seatId);
        return insertBooking(seatId, userId);
    }

    /**
     * OPTIMISTIC version: no lock while reading. Before inserting, bump the seat's
     * version only if it is still the version we read. If another booking got in
     * first, the bump touches 0 rows and we back out.
     */
    @Transactional
    public Booking bookSeatOptimistic(Long seatId, Long userId) {
        Optional<Seat> seat = seatRepository.findById(seatId);
        if (!seat.isPresent()) {
            throw new IllegalArgumentException("Seat not found: " + seatId);
        }
        Long versionWeRead = seat.get().getVersion();

        ensureSeatIsFree(seatId);

        int rowsUpdated = seatRepository.incrementVersionIfUnchanged(seatId, versionWeRead);
        if (rowsUpdated == 0) {
            throw new ConcurrentBookingException(seatId);
        }

        return insertBooking(seatId, userId);
    }

    private void ensureSeatIsFree(Long seatId) {
        List<BookingStatus> activeStatuses = Arrays.asList(BookingStatus.HELD, BookingStatus.CONFIRMED);
        boolean alreadyTaken = bookingRepository.existsBySeatIdAndStatusIn(seatId, activeStatuses);
        if (alreadyTaken) {
            throw new SeatAlreadyBookedException(seatId);
        }
    }

    private Booking insertBooking(Long seatId, Long userId) {
        Booking booking = new Booking();
        booking.setSeatId(seatId);
        booking.setUserId(userId);
        booking.setStatus(BookingStatus.CONFIRMED);
        return bookingRepository.saveAndFlush(booking);
    }
}
