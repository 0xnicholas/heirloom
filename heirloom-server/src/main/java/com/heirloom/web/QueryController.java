package com.heirloom.web;

import com.heirloom.repository.MappingRuleRepository;
import com.heirloom.repository.LineageRepository;
import com.heirloom.schema.service.PerspectiveEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final MappingRuleRepository mappingRepo;
    private final LineageRepository lineageRepo;
    private final DataSource dataSource;
    private final PerspectiveEngine perspective;

    public QueryController(MappingRuleRepository mappingRepo, LineageRepository lineageRepo,
                           DataSource dataSource, PerspectiveEngine perspective) {
        this.mappingRepo = mappingRepo;
        this.lineageRepo = lineageRepo;
        this.dataSource = dataSource;
        this.perspective = perspective;
    }

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> query(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        try {
            String type = (String) request.get("type");
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) request.getOrDefault("fields", List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) request.get("filter");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> traverse = (List<Map<String, String>>) request.get("traverse");

            // Phase 1.3: Perspective Engine — strip fields the actor can't see.
            // Done at the query plan stage (before SQL is generated) so the
            // database only ever returns allowed columns.
            String actorId = pickActor(role, agentId, user);
            fields = perspective.filterFields(type, actorId, fields);

            // Resolve field → physical column
            Map<String, String> fieldToCol = new LinkedHashMap<>();
            String table = null;
            for (String field : fields) {
                var rule = mappingRepo.findByTypeAndField(type, field);
                if (rule.isPresent()) {
                    String colFQN = rule.get().getColumnFQN();
                    String[] parts = colFQN.split("\\.");
                    if (parts.length >= 2) {
                        table = parts[parts.length - 3] + "." + parts[parts.length - 2];
                        fieldToCol.put(field, parts[parts.length - 1]);
                    }
                }
            }

            if (table == null) return ResponseEntity.ok(List.of(Map.of("error", "No mapping for type: " + type)));

            // Validate column names to prevent SQL injection
            for (String col : fieldToCol.values()) {
                if (!isValidSqlName(col)) return ResponseEntity.badRequest()
                    .body(List.of(Map.of("error", "Invalid column name: " + col)));
            }
            if (!isValidSqlName(table.replace(".", "_"))) return ResponseEntity.badRequest()
                .body(List.of(Map.of("error", "Invalid table name")));

            // Build SQL with optional traverse (JOIN)
            StringBuilder sql = new StringBuilder("SELECT ");
            List<String> selectParts = new ArrayList<>();
            fieldToCol.forEach((f, c) -> selectParts.add("t0." + c + " AS \"" + f + "\""));
            sql.append(String.join(", ", selectParts));
            sql.append(" FROM ").append(table).append(" t0");

            List<Object> params = new ArrayList<>();
            int joinIdx = 1;

            // Handle traverse — use LineageEntity for FK information
            if (traverse != null) {
                for (var step : traverse) {
                    String rel = step.get("relationship"); // e.g., "placed" or "customer"
                    String target = step.get("targetType"); // e.g., "Order"
                    
                    // Find lineage edges from this source
                    var lineages = lineageRepo.findAll();
                    for (var l : lineages) {
                        String fromTable = extractTable(l.getFromEntityFQN());
                        String toTable = extractTable(l.getToEntityFQN());
                        if (table.endsWith(fromTable)) {
                            sql.append(" LEFT JOIN ").append(toTable).append(" t").append(joinIdx)
                               .append(" ON t0.id = t").append(joinIdx).append(".").append(rel).append("_id");
                            joinIdx++;
                            break;
                        }
                    }
                }
            }

            if (filter != null && filter.containsKey("field")) {
                String col = fieldToCol.getOrDefault(filter.get("field"), (String) filter.get("field"));
                sql.append(" WHERE t0.").append(col).append(" ").append(mapOp((String) filter.get("op"))).append(" ?");
                params.add(filter.get("value"));
            }

            if (request.containsKey("limit")) sql.append(" LIMIT ").append(request.get("limit"));

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
            return ResponseEntity.internalServerError().body(List.of(Map.of("error", e.getMessage())));
        }
    }

    private static boolean isValidSqlName(String name) {
        return name != null && name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private static String pickActor(String role, String agentId, String user) {
        if (role != null && !role.isBlank()) return role;
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (user != null && !user.isBlank()) return user;
        return "system";
    }

    private String extractTable(String fqn) {
        String[] parts = fqn.split("\\.");
        if (parts.length >= 2) return parts[parts.length - 2] + "." + parts[parts.length - 1];
        return fqn;
    }

    private String mapOp(String op) {
        return switch (op != null ? op : "$eq") {
            case "$eq" -> "="; case "$neq" -> "!="; case "$gt" -> ">";
            case "$gte" -> ">="; case "$lt" -> "<"; case "$lte" -> "<=";
            case "$like" -> "LIKE"; case "$in" -> "IN"; default -> "=";
        };
    }
}
