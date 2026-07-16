package com.heirloom.web;

import com.heirloom.core.query.QueryParseException;
import com.heirloom.query.SemanticExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final SemanticExecutor semanticExecutor;

    public QueryController(SemanticExecutor semanticExecutor) {
        this.semanticExecutor = semanticExecutor;
    }

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> query(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        try {
            String actorId = pickActor(role, agentId, user);
            return ResponseEntity.ok(semanticExecutor.execute(request, actorId).rows());
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