package com.heirloom.discovery.model;

public record RawColumn(String columnName, String rawType, boolean nullable, String comment, String defaultValue) {}
