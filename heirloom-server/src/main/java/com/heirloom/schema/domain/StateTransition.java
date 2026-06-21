package com.heirloom.schema.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * A single directed transition in a Resource Type's state machine.
 * States are defined implicitly by the set of from/to pairs across
 * all transitions — there is no separate state registry.
 */
public record StateTransition(
        @NotBlank String from,
        @NotBlank String to,
        String label
) {
    public StateTransition {
        // compact constructor — label is nullable
    }

    public StateTransition(String from, String to) {
        this(from, to, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateTransition(String f, String t, String l))) return false;
        return from.equals(f) && to.equals(t) && Objects.equals(label, l);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, label);
    }
}
