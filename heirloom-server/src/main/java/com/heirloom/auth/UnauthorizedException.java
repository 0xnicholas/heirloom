package com.heirloom.auth;

public class UnauthorizedException extends RuntimeException {
    private final String entityType;
    private final String operation;

    public UnauthorizedException(String entityType, String operation, String message) {
        super(message);
        this.entityType = entityType;
        this.operation = operation;
    }

    public String getEntityType() { return entityType; }
    public String getOperation() { return operation; }
}
