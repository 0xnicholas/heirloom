package com.heirloom.schema.service;

public class TypeNotFoundException extends RuntimeException {
    public TypeNotFoundException(String name) {
        super("Resource Type \"" + name + "\" not found");
    }
}
