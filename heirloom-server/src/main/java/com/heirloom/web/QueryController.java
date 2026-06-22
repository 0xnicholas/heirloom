package com.heirloom.web;

import com.heirloom.repository.MappingRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final MappingRuleRepository mappingRepo;
    private final DataSource dataSource;

    public QueryController(MappingRuleRepository mappingRepo, DataSource dataSource) {
        this.mappingRepo = mappingRepo;
        this.dataSource = dataSource;
    }

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> query(@RequestBody Map<String, Object> request) {
        try {
            String type = (String) request.get("type");
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) request.getOrDefault("fields", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) request.get("filter");

            // Resolve field → physical column via MappingRule
            List<String> selectCols = new ArrayList<>();
            String table = null;
            for (String field : fields) {
                var rule = mappingRepo.findByTypeAndField(type, field);
                if (rule.isPresent()) {
                    String colFQN = rule.get().getColumnFQN();
                    // Parse column FQN: service.db.schema.table.column → schema.table.column
                    String[] parts = colFQN.split("\\.");
                    if (parts.length >= 2) {
                        table = parts[parts.length - 3] + "." + parts[parts.length - 2]; // schema.table
                        selectCols.add(parts[parts.length - 1]); // column
                    }
                }
            }

            if (table == null || selectCols.isEmpty()) {
                return ResponseEntity.ok(List.of(Map.of("error", "No mapping found for type: " + type)));
            }

            // Build SQL
            StringBuilder sql = new StringBuilder("SELECT " + String.join(", ", selectCols) + " FROM " + table);
            List<Object> params = new ArrayList<>();

            if (filter != null && filter.containsKey("field")) {
                String op = mapOp((String) filter.get("op"));
                sql.append(" WHERE ").append(filter.get("field")).append(" ").append(op).append(" ?");
                params.add(filter.get("value"));
            }

            if (request.containsKey("limit")) {
                sql.append(" LIMIT ").append(request.get("limit"));
            }

            // Execute
            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    var meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++)
                            row.put(meta.getColumnName(i), rs.getObject(i));
                        results.add(row);
                    }
                }
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(List.of(Map.of("error", e.getMessage())));
        }
    }

    private String mapOp(String op) {
        return switch (op != null ? op : "$eq") {
            case "$eq" -> "="; case "$neq" -> "!="; case "$gt" -> ">";
            case "$gte" -> ">="; case "$lt" -> "<"; case "$lte" -> "<=";
            case "$like" -> "LIKE"; case "$in" -> "IN";
            default -> "=";
        };
    }
}
