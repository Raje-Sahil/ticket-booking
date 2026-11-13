package com.sahil.ticketbooking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.model.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Spring builds the SQL from the method name:
    // SELECT ... FROM bookings WHERE seat_id = ? AND status IN (...)
    boolean existsBySeatIdAndStatusIn(Long seatId, Collection<BookingStatus> statuses);

    // IDs of the seats of one event that currently have an active booking.
    @Query("SELECT b.seatId FROM Booking b WHERE b.status IN (:statuses) "
            + "AND b.seatId IN (SELECT s.id FROM Seat s WHERE s.eventId = :eventId)")
    List<Long> findActiveSeatIdsForEvent(@Param("eventId") Long eventId,
                                         @Param("statuses") Collection<BookingStatus> statuses);
}
