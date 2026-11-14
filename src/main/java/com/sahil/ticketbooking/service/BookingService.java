package com.sahil.ticketbooking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.model.BookingStatus;
import com.sahil.ticketbooking.model.Seat;
import com.sahil.ticketbooking.repository.BookingRepository;
import com.sahil.ticketbooking.repository.SeatRepository;
import com.sahil.ticketbooking.repository.UserRepository;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final long holdMinutes;

    public BookingService(BookingRepository bookingRepository,
                          SeatRepository seatRepository,
                          UserRepository userRepository,
                          @Value("${booking.hold-minutes:5}") long holdMinutes) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.holdMinutes = holdMinutes;
    }

    // ------------------------------------------------------------------
    // Methods used by the REST API
    // ------------------------------------------------------------------

    /** Books a seat immediately (status CONFIRMED). */
    @Transactional
    public Booking bookSeat(Long seatId, Long userId) {
        requireUser(userId);
        lockSeat(seatId);
        expireStaleHolds(seatId);
        ensureSeatIsFree(seatId);
        return insertBooking(seatId, userId, BookingStatus.CONFIRMED, null);
    }

    /** Holds a seat for a few minutes (status HELD) while the user pays. */
    @Transactional
    public Booking holdSeat(Long seatId, Long userId) {
        requireUser(userId);
        lockSeat(seatId);
        expireStaleHolds(seatId);
        ensureSeatIsFree(seatId);
        Instant expiresAt = Instant.now().plus(holdMinutes, ChronoUnit.MINUTES);
        return insertBooking(seatId, userId, BookingStatus.HELD, expiresAt);
    }

    /** Turns a live hold into a confirmed booking. Confirming twice is harmless. */
    @Transactional
    public Booking confirmBooking(Long bookingId, Long userId) {
        Booking booking = loadOwnBookingForUpdate(bookingId, userId);

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return booking;
        }
        if (booking.getStatus() != BookingStatus.HELD) {
            throw new InvalidBookingStateException(
                    "Booking is " + booking.getStatus() + ", only a held booking can be confirmed");
        }
        if (booking.getHoldExpiresAt() == null || booking.getHoldExpiresAt().isBefore(Instant.now())) {
            throw new InvalidBookingStateException("The hold has expired");
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setHoldExpiresAt(null);
        return bookingRepository.saveAndFlush(booking);
    }

    /** Cancels a hold or a confirmed booking. Cancelling twice is harmless. */
    @Transactional
    public Booking cancelBooking(Long bookingId, Long userId) {
        Booking booking = loadOwnBookingForUpdate(bookingId, userId);

        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.EXPIRED) {
            return booking;
        }

        booking.setStatus(BookingStatus.CANCELLED);
        return bookingRepository.saveAndFlush(booking);
    }

    // ------------------------------------------------------------------
    // The three strategies compared in the tests and benchmark (unchanged)
    // ------------------------------------------------------------------

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
     * version only if it is still the version we read.
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
    }

    private void lockSeat(Long seatId) {
        Optional<Seat> seat = seatRepository.findByIdForUpdate(seatId);
        if (!seat.isPresent()) {
            throw new ResourceNotFoundException("Seat not found: " + seatId);
        }
    }

    // Frees the seat from holds whose time has run out, so they cannot block a new booking.
    private void expireStaleHolds(Long seatId) {
        bookingRepository.expireStaleHolds(seatId, Instant.now(), BookingStatus.HELD, BookingStatus.EXPIRED);
    }

    // A booking that belongs to someone else is reported as "not found".
    private Booking loadOwnBookingForUpdate(Long bookingId, Long userId) {
        Optional<Booking> found = bookingRepository.findByIdForUpdate(bookingId);
        if (!found.isPresent() || !found.get().getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found: " + bookingId);
        }
        return found.get();
    }

    private void ensureSeatIsFree(Long seatId) {
        List<BookingStatus> activeStatuses = Arrays.asList(BookingStatus.HELD, BookingStatus.CONFIRMED);
        boolean alreadyTaken = bookingRepository.existsBySeatIdAndStatusIn(seatId, activeStatuses);
        if (alreadyTaken) {
            throw new SeatAlreadyBookedException(seatId);
        }
    }

    private Booking insertBooking(Long seatId, Long userId) {
        return insertBooking(seatId, userId, BookingStatus.CONFIRMED, null);
    }

    private Booking insertBooking(Long seatId, Long userId, BookingStatus status, Instant holdExpiresAt) {
        Booking booking = new Booking();
        booking.setSeatId(seatId);
        booking.setUserId(userId);
        booking.setStatus(status);
        booking.setHoldExpiresAt(holdExpiresAt);
        return bookingRepository.saveAndFlush(booking);
    }
}
