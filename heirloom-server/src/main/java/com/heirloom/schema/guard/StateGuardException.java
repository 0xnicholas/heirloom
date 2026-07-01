package com.heirloom.schema.guard;

/**
 * Thrown when a state transition is rejected at runtime.
 */
public class StateGuardException extends RuntimeException {

    public StateGuardException(String message) {
        super(message);
    }
}
