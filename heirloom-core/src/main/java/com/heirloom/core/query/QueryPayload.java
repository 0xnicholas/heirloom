package com.heirloom.core.query;
import java.util.List;
import java.util.Map;
public record QueryPayload(
    String type,
    Map<String, Object> filter,
    List<String> fields
) {}
