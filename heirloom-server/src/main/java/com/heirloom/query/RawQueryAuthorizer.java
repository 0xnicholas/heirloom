package com.heirloom.query;

import com.heirloom.auth.UnauthorizedException;
import com.heirloom.repository.TableRepository;
import org.springframework.stereotype.Component;

@Component
public class RawQueryAuthorizer {

    private final TableRepository tableRepo;

    public RawQueryAuthorizer(TableRepository tableRepo) {
        this.tableRepo = tableRepo;
    }

    public void check(String tableFQN, String sql) {
        if (tableRepo.findByFQN(tableFQN).isEmpty()) {
            throw new UnauthorizedException(
                "raw_query", "SELECT",
                "Raw query not allowed on unregistered table: " + tableFQN);
        }

        String cleaned = stripComments(sql.trim());
        int semiIdx = cleaned.indexOf(';');
        String firstStmt = semiIdx >= 0 ? cleaned.substring(0, semiIdx).trim() : cleaned.trim();
        if (semiIdx >= 0 && !stripComments(cleaned.substring(semiIdx + 1)).trim().isEmpty()) {
            throw new UnauthorizedException(
                "raw_query", "SELECT",
                "Multi-statement raw queries are not allowed");
        }

        String upper = firstStmt.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH") && !upper.startsWith("EXPLAIN")) {
            throw new UnauthorizedException(
                "raw_query", "SELECT",
                "Only SELECT queries allowed in raw mode");
        }

        if (!upper.contains("LIMIT")) {
            throw new UnauthorizedException(
                "raw_query", "SELECT",
                "Raw query must include LIMIT clause");
        }
    }

    private String stripComments(String sql) {
        sql = sql.replaceAll("--[^\n]*", "");
        sql = sql.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
        return sql;
    }
}