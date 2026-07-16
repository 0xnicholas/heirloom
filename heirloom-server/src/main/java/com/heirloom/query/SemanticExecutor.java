package com.heirloom.query;

import com.heirloom.core.query.GeneratedSql;
import com.heirloom.core.query.SemanticQuery;
import com.heirloom.schema.service.PerspectiveEngine;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SemanticExecutor {

    private final QueryParser queryParser;
    private final SqlGenerator sqlGenerator;
    private final DataSource dataSource;
    private final PerspectiveEngine perspective;

    public SemanticExecutor(QueryParser queryParser, SqlGenerator sqlGenerator,
                            DataSource dataSource, PerspectiveEngine perspective) {
        this.queryParser = queryParser;
        this.sqlGenerator = sqlGenerator;
        this.dataSource = dataSource;
        this.perspective = perspective;
    }

    public SemanticResult execute(Map<String, Object> payload, String actorId) throws Exception {
        SemanticQuery query = queryParser.parse(payload);

        List<String> filteredFields = perspective.filterFields(
                query.getType(), actorId, query.getFields());

        SemanticQuery filtered = new SemanticQuery(
                query.getType(), filteredFields, query.getFilter(),
                query.getTraverse(), query.getAggregate(),
                query.getSortField(), query.getSortDirection(),
                query.getLimit(), query.getOffset());

        GeneratedSql gen = sqlGenerator.generate(filtered);

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

        return new SemanticResult(results, results.size());
    }

    public record SemanticResult(List<Map<String, Object>> rows, long count) {}
}