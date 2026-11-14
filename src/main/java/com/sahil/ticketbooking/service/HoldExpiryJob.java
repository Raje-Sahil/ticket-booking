package com.sahil.ticketbooking.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sahil.ticketbooking.model.BookingStatus;
import com.sahil.ticketbooking.repository.BookingRepository;

/**
 * Housekeeping: marks holds that have run out as EXPIRED.
 * Correctness does not depend on this job, because holds are also expired
 * on demand whenever someone books or holds the same seat.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final BookingRepository bookingRepository;

    public HoldExpiryJob(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Scheduled(fixedDelayString = "${booking.expiry-job-interval-ms:30000}",
            initialDelayString = "${booking.expiry-job-interval-ms:30000}")
    @Transactional
    public void expireStaleHolds() {
        int expired = bookingRepository.expireAllStaleHolds(
                Instant.now(), BookingStatus.HELD, BookingStatus.EXPIRED);
        if (expired > 0) {
            log.info("Expired {} stale hold(s)", expired);
        }
    }
}
