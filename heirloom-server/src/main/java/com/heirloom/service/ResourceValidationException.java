package com.heirloom.service;

/**
 * Thrown when a Resource operation fails validation — missing type,
 * illegal fields, type mismatch, or invalid state transition.
 */
public class ResourceValidationException extends RuntimeException {

    public ResourceValidationException(String message) {
        super(message);
    }
}
