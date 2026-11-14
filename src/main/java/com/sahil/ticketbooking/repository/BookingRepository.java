package com.sahil.ticketbooking.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.model.BookingStatus;

import jakarta.persistence.LockModeType;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Spring builds the SQL from the method name:
    // SELECT ... FROM bookings WHERE seat_id = ? AND status IN (...)
    boolean existsBySeatIdAndStatusIn(Long seatId, Collection<BookingStatus> statuses);

    // Loads one booking and locks its row (SELECT ... FOR UPDATE).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    // Marks this seat's holds that have run out as EXPIRED. Returns how many were changed.
    @Modifying
    @Query("UPDATE Booking b SET b.status = :expired "
            + "WHERE b.seatId = :seatId AND b.status = :held AND b.holdExpiresAt < :now")
    int expireStaleHolds(@Param("seatId") Long seatId,
                         @Param("now") Instant now,
                         @Param("held") BookingStatus held,
                         @Param("expired") BookingStatus expired);

    // Seats of one event that are NOT available right now:
    // confirmed bookings, plus holds that have not yet run out.
    @Query("SELECT b.seatId FROM Booking b "
            + "WHERE b.seatId IN (SELECT s.id FROM Seat s WHERE s.eventId = :eventId) "
            + "AND (b.status = :confirmed OR (b.status = :held AND b.holdExpiresAt > :now))")
    List<Long> findUnavailableSeatIdsForEvent(@Param("eventId") Long eventId,
                                              @Param("now") Instant now,
                                              @Param("held") BookingStatus held,
                                              @Param("confirmed") BookingStatus confirmed);
}
