package com.heirloom.core.alignment;
import java.util.List;

public record AlignmentRequest(
    String tableFQN,
    List<String> targetOntologies,
    boolean allowNewType
) {}
