package com.sahil.ticketbooking.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sahil.ticketbooking.model.Seat;

import jakarta.persistence.LockModeType;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // Pessimistic: loads the seat and locks its row (SELECT ... FOR UPDATE).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    // Optimistic: bump the version only if nobody else changed it since we read it.
    // Returns the number of rows updated: 1 = we won, 0 = someone else got there first.
    @Modifying
    @Query(value = "UPDATE seats SET version = version + 1 WHERE id = :id AND version = :version",
            nativeQuery = true)
    int incrementVersionIfUnchanged(@Param("id") Long id, @Param("version") Long version);
}
