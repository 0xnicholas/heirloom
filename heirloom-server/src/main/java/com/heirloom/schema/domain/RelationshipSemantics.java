package com.heirloom.schema.domain;

/**
 * Three relationship semantics that govern lifecycle coupling,
 * permission propagation, and cascade behavior.
 */
public enum RelationshipSemantics {
    OWNERSHIP,
    REFERENCE,
    ASSOCIATION
}
