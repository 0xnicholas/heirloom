package com.heirloom.core.query;
import java.util.Map;
public record RouteStep(
    String engine,
    QueryPayload payload,
    Map<String, Object> bindings
) {}
