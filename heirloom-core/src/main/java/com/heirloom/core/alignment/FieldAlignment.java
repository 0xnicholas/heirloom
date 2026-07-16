package com.heirloom.core.alignment;
import java.util.List;

public record FieldAlignment(
    String columnName,
    SemanticTarget target,
    double confidence,
    List<AlignmentSignal> signals
) {}
