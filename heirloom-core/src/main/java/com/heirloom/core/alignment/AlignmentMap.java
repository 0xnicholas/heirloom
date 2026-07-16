package com.heirloom.core.alignment;
import java.util.List;

public record AlignmentMap(
    String tableFQN,
    List<FieldAlignment> alignments,
    List<String> unmappedColumns,
    List<NewTypeSuggestion> newTypeSuggestions
) {}
