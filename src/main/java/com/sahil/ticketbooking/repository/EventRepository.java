package com.sahil.ticketbooking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sahil.ticketbooking.model.Event;

public interface EventRepository extends JpaRepository<Event, Long> {
}
