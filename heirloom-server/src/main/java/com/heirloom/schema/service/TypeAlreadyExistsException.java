package com.heirloom.schema.service;

public class TypeAlreadyExistsException extends RuntimeException {
    public TypeAlreadyExistsException(String name) {
        super("Resource Type \"" + name + "\" already exists");
    }
}
