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

    /** Books a seat immediately (status CONFIRMED). The idempotency key may be null. */
    @Transactional
    public BookingOutcome bookSeat(Long seatId, Long userId, String idempotencyKey) {
        return createBooking(seatId, userId, BookingStatus.CONFIRMED, null, idempotencyKey);
    }

    /** Holds a seat for a few minutes (status HELD). The idempotency key may be null. */
    @Transactional
    public BookingOutcome holdSeat(Long seatId, Long userId, String idempotencyKey) {
        Instant expiresAt = Instant.now().plus(holdMinutes, ChronoUnit.MINUTES);
        return createBooking(seatId, userId, BookingStatus.HELD, expiresAt, idempotencyKey);
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

    private BookingOutcome createBooking(Long seatId, Long userId, BookingStatus status,
                                         Instant holdExpiresAt, String idempotencyKey) {
        requireUser(userId);
        lockSeat(seatId);

        // The key is checked AFTER the seat lock is taken. Concurrent retries of the
        // same request line up behind the lock, so each one sees the first one's booking.
        if (idempotencyKey != null) {
            Optional<Booking> existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Booking earlier = existing.get();
                if (!earlier.getSeatId().equals(seatId) || !earlier.getUserId().equals(userId)) {
                    throw new IdempotencyKeyMismatchException(idempotencyKey);
                }
                return new BookingOutcome(earlier, true);
            }
        }

        expireStaleHolds(seatId);
        ensureSeatIsFree(seatId);
        Booking created = insertBooking(seatId, userId, status, holdExpiresAt, idempotencyKey);
        return new BookingOutcome(created, false);
    }

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
        return insertBooking(seatId, userId, BookingStatus.CONFIRMED, null, null);
    }

    private Booking insertBooking(Long seatId, Long userId, BookingStatus status,
                                  Instant holdExpiresAt, String idempotencyKey) {
        Booking booking = new Booking();
        booking.setSeatId(seatId);
        booking.setUserId(userId);
        booking.setStatus(status);
        booking.setHoldExpiresAt(holdExpiresAt);
        booking.setIdempotencyKey(idempotencyKey);
        return bookingRepository.saveAndFlush(booking);
    }
}
