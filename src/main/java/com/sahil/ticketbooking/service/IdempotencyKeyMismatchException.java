package com.sahil.ticketbooking.service;

public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String key) {
        super("Idempotency key '" + key + "' was already used for a different request");
    }
}
