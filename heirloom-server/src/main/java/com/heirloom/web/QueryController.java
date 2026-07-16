package com.heirloom.web;

import com.heirloom.core.query.*;
import com.heirloom.query.QueryRouter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final QueryRouter queryRouter;

    public QueryController(QueryRouter queryRouter) {
        this.queryRouter = queryRouter;
    }

    @PostMapping
    public ResponseEntity<?> query(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        try {
            String actorId = pickActor(role, agentId, user);
            QueryRequest qr = toQueryRequest(request);
            QueryResult result = queryRouter.execute(qr);
            if (isLegacyShape(request)) {
                return ResponseEntity.ok(result.rows());
            }
            return ResponseEntity.ok(result);
        } catch (QueryParseException | IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isLegacyShape(Map<String, Object> request) {
        return !request.containsKey("mode")
            && (request.containsKey("type") || request.containsKey("filter"))
            && !request.containsKey("rawTable")
            && !request.containsKey("rawSql")
            && !request.containsKey("resource")
            && !request.containsKey("drillDown")
            && !request.containsKey("payload");
    }

    private QueryRequest toQueryRequest(Map<String, Object> request) {
        if (isLegacyShape(request)) {
            String type = (String) request.get("type");
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) request.get("filter");
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) request.get("fields");
            QueryPayload payload = new QueryPayload(type, filter, fields);
            return new QueryRequest(QueryMode.SEMANTIC, payload, null, null, null, null);
        }

        QueryMode mode = request.get("mode") != null
            ? QueryMode.valueOf(request.get("mode").toString())
            : QueryMode.AUTO;
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) request.get("payload");
        QueryPayload payload = null;
        if (payloadMap != null) {
            String type = (String) payloadMap.get("type");
            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) payloadMap.get("filter");
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) payloadMap.get("fields");
            payload = new QueryPayload(type, filter, fields);
        }
        String rawTable = (String) request.get("rawTable");
        String rawSql = (String) request.get("rawSql");

        ResourceRef resource = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> resMap = (Map<String, Object>) request.get("resource");
        if (resMap != null) {
            resource = new ResourceRef(
                (String) resMap.get("type"),
                (String) resMap.get("rid"),
                (List<String>) resMap.get("fields")
            );
        }

        DrillDown drillDown = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> ddMap = (Map<String, Object>) request.get("drillDown");
        if (ddMap != null) {
            drillDown = new DrillDown((String) ddMap.get("rawTable"), (String) ddMap.get("rawSql"));
        }

        return new QueryRequest(mode, payload, rawTable, rawSql, resource, drillDown);
    }

    private static String pickActor(String role, String agentId, String user) {
        if (role != null && !role.isBlank()) return role;
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (user != null && !user.isBlank()) return user;
        return "system";
    }
}