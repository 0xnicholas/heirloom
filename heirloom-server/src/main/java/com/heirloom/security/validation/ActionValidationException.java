package com.heirloom.security.validation;

/**
 * Thrown when an Action definition fails validation against ADR-007 rules.
 */
public class ActionValidationException extends RuntimeException {

    public ActionValidationException(String message) {
        super(message);
    }
}
