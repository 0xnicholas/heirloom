package com.heirloom.web;

import com.heirloom.knowledge.service.NLQService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 3.3: Natural Language Query endpoint.
 * Translates natural language to JSON DSL via LLM and executes it.
 */
@RestController
@RequestMapping("/v1/query")
public class NLQController {

    private final NLQService nlqService;

    public NLQController(NLQService nlqService) {
        this.nlqService = nlqService;
    }

    @PostMapping("/nlq")
    public ResponseEntity<?> ask(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {

        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "question is required"));
        }

        String mode = request.get("mode");
        String actor = agentId != null ? agentId : "user";

        var result = nlqService.ask(question, mode, actor);

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "generatedQuery", result.generatedQuery(),
                "result", result.queryResult()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "success", false,
            "generatedQuery", result.generatedQuery() != null ? result.generatedQuery() : "",
            "error", result.error()
        ));
    }

    @PostMapping("/nlq/translate")
    public ResponseEntity<?> translate(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "question is required"));
        }

        String generatedJson = nlqService.translate(question);
        if (generatedJson == null) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "LLM failed to generate a query. Check OPENAI_API_KEY."
            ));
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "generatedQuery", generatedJson
        ));
    }
}
