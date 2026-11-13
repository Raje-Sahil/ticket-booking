package com.sahil.ticketbooking.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sahil.ticketbooking.model.Booking;
import com.sahil.ticketbooking.model.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Spring builds the SQL from the method name:
    // SELECT ... FROM bookings WHERE seat_id = ? AND status IN (...)
    boolean existsBySeatIdAndStatusIn(Long seatId, Collection<BookingStatus> statuses);
}
