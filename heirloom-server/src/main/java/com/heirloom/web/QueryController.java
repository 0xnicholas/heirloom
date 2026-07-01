package com.heirloom.web;

import com.heirloom.query.*;
import com.heirloom.schema.service.PerspectiveEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Semantic query endpoint — now backed by QueryParser + SqlGenerator
 * for type-safe, parameterized SQL generation. API contract unchanged.
 */
@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final QueryParser queryParser;
    private final SqlGenerator sqlGenerator;
    private final DataSource dataSource;
    private final PerspectiveEngine perspective;

    public QueryController(QueryParser queryParser, SqlGenerator sqlGenerator,
                           DataSource dataSource, PerspectiveEngine perspective) {
        this.queryParser = queryParser;
        this.sqlGenerator = sqlGenerator;
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
            String actorId = pickActor(role, agentId, user);

            // Parse and validate (type exists, fields declared, ops legal)
            SemanticQuery query = queryParser.parse(request);

            // Perspective Engine — strip hidden fields
            List<String> filteredFields = perspective.filterFields(
                    query.getType(), actorId, query.getFields());

            // Rebuild query with filtered fields
            SemanticQuery filtered = new SemanticQuery(
                    query.getType(), filteredFields, query.getFilter(),
                    query.getTraverse(), query.getAggregate(),
                    query.getSortField(), query.getSortDirection(),
                    query.getLimit(), query.getOffset());

            // Generate parameterized SQL
            GeneratedSql gen = sqlGenerator.generate(filtered);

            // Execute
            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(gen.sql())) {
                for (int i = 0; i < gen.params().size(); i++) {
                    ps.setObject(i + 1, gen.params().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    var meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnName(i), rs.getObject(i));
                        }
                        results.add(row);
                    }
                }
            }

            return ResponseEntity.ok(results);
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(List.of(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(List.of(Map.of("error", e.getMessage())));
        }
    }

    private static String pickActor(String role, String agentId, String user) {
        if (role != null && !role.isBlank()) return role;
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (user != null && !user.isBlank()) return user;
        return "system";
    }
}
