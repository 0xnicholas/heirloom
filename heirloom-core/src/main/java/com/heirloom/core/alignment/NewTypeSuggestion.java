package com.heirloom.core.alignment;
import java.util.List;

public record NewTypeSuggestion(
    String proposedTypeName,
    List<String> columns,
    double confidence,
    String rationale
) {}
