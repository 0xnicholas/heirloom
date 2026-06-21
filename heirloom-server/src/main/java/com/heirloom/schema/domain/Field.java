package com.heirloom.schema.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A single field definition within a Resource Type.
 */
public record Field(
        @NotBlank String name,
        @NotNull FieldType type,
        boolean required,
        List<String> enumValues
) {
    public Field {
        enumValues = enumValues != null ? List.copyOf(enumValues) : List.of();
    }

    public Field(String name, FieldType type, boolean required) {
        this(name, type, required, List.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Field(String n, FieldType t, boolean r, List<?> ev))) return false;
        return required == r && name.equals(n) && type == t && Objects.equals(enumValues, ev);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, required, enumValues);
    }
}
