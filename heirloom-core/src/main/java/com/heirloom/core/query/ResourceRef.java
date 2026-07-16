package com.heirloom.core.query;
import java.util.List;
public record ResourceRef(
    String type,
    String rid,
    List<String> fields
) {}
