package com.heirloom.schema.domain;

/**
 * The eight ability markers that define what operations are permissible
 * on a Resource Type. These are type-level contracts — a type that does
 * not declare an ability makes that operation structurally impossible.
 */
public enum Ability {
    KEY,
    STORE,
    QUERY,
    MUTATE,
    TRANSFER,
    COPY,
    DROP,
    FREEZE
}
