package com.sahil.ticketbooking.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sahil.ticketbooking.service.ConcurrentBookingException;
import com.sahil.ticketbooking.service.IdempotencyKeyMismatchException;
import com.sahil.ticketbooking.service.InvalidBookingStateException;
import com.sahil.ticketbooking.service.ResourceNotFoundException;
import com.sahil.ticketbooking.service.SeatAlreadyBookedException;

/**
 * Turns exceptions from the service layer into clean HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SeatAlreadyBookedException.class)
    public ProblemDetail handleSeatAlreadyBooked(SeatAlreadyBookedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ConcurrentBookingException.class)
    public ProblemDetail handleConcurrentBooking(ConcurrentBookingException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidBookingStateException.class)
    public ProblemDetail handleInvalidState(InvalidBookingStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ProblemDetail handleKeyMismatch(IdempotencyKeyMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // Last line of defence: the database unique index rejected the insert.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The booking conflicts with existing data");
    }
}
