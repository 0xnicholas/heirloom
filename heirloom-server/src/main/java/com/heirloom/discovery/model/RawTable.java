package com.heirloom.discovery.model;

import java.util.List;

public record RawTable(String schemaName, String tableName, String comment,
                        List<RawColumn> columns, List<RawConstraint> constraints, Long rowCount) {}
