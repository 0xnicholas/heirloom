package com.heirloom.schema.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

/**
 * A typed, directed relationship between two Resource Types.
 * The semantics (Ownership / Reference / Association)
 * determine lifecycle coupling, permission propagation, and
 * cascade behavior.
 */
public record Relationship(
        @NotBlank String label,
        @NotBlank String targetType,
        @NotNull RelationshipSemantics semantics
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Relationship(String l, String t, RelationshipSemantics s))) return false;
        return label.equals(l) && targetType.equals(t) && semantics == s;
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, targetType, semantics);
    }
}
