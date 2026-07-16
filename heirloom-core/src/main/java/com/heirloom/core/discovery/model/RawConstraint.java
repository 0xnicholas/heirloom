package com.heirloom.core.discovery.model;

import java.util.List;

public record RawConstraint(ConstraintType type, List<String> columns, String targetTable,
                             List<String> targetColumns, String deleteRule, String checkExpression) {
    public enum ConstraintType { PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK }
}
