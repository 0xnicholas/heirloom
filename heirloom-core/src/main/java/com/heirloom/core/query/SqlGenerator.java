package com.heirloom.core.query;

public interface SqlGenerator {
    GeneratedSql generate(SemanticQuery query);
}
