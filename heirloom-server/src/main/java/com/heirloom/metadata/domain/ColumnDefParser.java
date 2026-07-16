package com.heirloom.metadata.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.metadata.ColumnDef;
import java.util.*;

public class ColumnDefParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static List<ColumnDef> parse(String columnsJson) {
        try {
            ColumnDef[] defs = mapper.readValue(columnsJson, ColumnDef[].class);
            return List.of(defs);
        } catch (Exception e) {
            return parseLegacy(columnsJson);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ColumnDef> parseLegacy(String json) {
        try {
            List<Map<String,Object>> legacy = mapper.readValue(json, List.class);
            List<ColumnDef> result = new ArrayList<>();
            int i = 0;
            for (Map<String,Object> col : legacy) {
                result.add(new ColumnDef(
                    (String) col.getOrDefault("name", "col_" + i),
                    (String) col.getOrDefault("dataType",
                        (String) col.getOrDefault("type", "varchar")),
                    col.containsKey("dataLength") ? ((Number) col.get("dataLength")).intValue() : null,
                    null, null,
                    false, null,
                    (String) col.getOrDefault("comment", null),
                    i,
                    List.of()
                ));
                i++;
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
