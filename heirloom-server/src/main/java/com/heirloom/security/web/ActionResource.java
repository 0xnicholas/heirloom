package com.heirloom.security.web;

import com.heirloom.security.pipeline.PipelineRejection;
import com.heirloom.security.pipeline.PipelineResult;
import com.heirloom.security.service.ActionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * REST endpoint for Action execution.
 * <p>
 * POST /v1/actions/{actionName}/execute
 */
@RestController
@RequestMapping("/v1/actions")
public class ActionResource {

    private final ActionService actionService;

    public ActionResource(ActionService actionService) {
        this.actionService = actionService;
    }

    @PostMapping("/{actionName}/execute")
    public ResponseEntity<?> execute(
            @PathVariable String actionName,
            @RequestHeader(value = "X-Actor-Type", required = false) String actorType,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
            @RequestBody Map<String, Object> request) {

        String targetResourceId = (String) request.get("targetResourceId");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        try {
            PipelineResult result = actionService.execute(
                    actionName, actorType, actorId, actorRole, targetResourceId, params);

            if ("DENIED".equals(result.status())) {
                int httpStatus = switch (result.deniedAtName()) {
                    case "AUTH" -> 401;
                    case "ROLE", "CAPABILITY", "GATE" -> 403;
                    case "STATE" -> 409;
                    case "VALIDATE" -> 422;
                    default -> 500;
                };
                return ResponseEntity.status(httpStatus).body(result);
            }
            return ResponseEntity.ok(result);

        } catch (PipelineRejection e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, e.getMessage());
            pd.setTitle("Pipeline execution failed");
            pd.setType(URI.create("https://heirloom.dev/errors/pipeline-rejection"));
            pd.setProperty("step", e.getStep());
            pd.setProperty("stepName", e.getStepName());
            return ResponseEntity.badRequest().body(pd);
        }
    }
}
