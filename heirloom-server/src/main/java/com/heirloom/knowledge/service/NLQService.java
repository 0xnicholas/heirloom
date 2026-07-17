package com.heirloom.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.query.*;
import com.heirloom.query.QueryRouter;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Phase 3.3: Natural Language Query service.
 * Translates natural language questions into Heirloom JSON DSL
 * using an LLM (OpenAI chat completions), then executes the query.
 */
@Service
public class NLQService {

    private static final Logger log = LoggerFactory.getLogger(NLQService.class);

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    private final RestTemplate rest;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TypeRepository typeRepo;
    private final QueryRouter queryRouter;

    public NLQService(TypeRepository typeRepo, QueryRouter queryRouter) {
        this.rest = new RestTemplate();
        this.apiKey = System.getenv("OPENAI_API_KEY");
        this.typeRepo = typeRepo;
        this.queryRouter = queryRouter;
    }

    /**
     * Translate a natural language question to JSON DSL and execute it.
     */
    public NLQResult ask(String question, String mode, String actorId) {
        // 1. Build schema context from the registry
        String schemaContext = buildSchemaContext();

        // 2. Call LLM to generate the JSON DSL query
        String generatedJson = callLlm(question, schemaContext);
        if (generatedJson == null) {
            return new NLQResult(false, null, null, "LLM failed to generate a query. Try rephrasing your question.");
        }

        // 3. Parse and execute
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> queryMap = mapper.readValue(generatedJson, Map.class);
            QueryMode queryMode = mode != null ? QueryMode.valueOf(mode.toUpperCase()) : QueryMode.AUTO;

            QueryRequest request = buildQueryRequest(queryMap, queryMode);
            QueryResult result = queryRouter.execute(request);

            return new NLQResult(true, generatedJson, result, null);
        } catch (QueryParseException e) {
            return new NLQResult(false, generatedJson, null,
                "Query parsing error: " + e.getMessage()
                + ". The generated JSON was: " + generatedJson);
        } catch (Exception e) {
            return new NLQResult(false, generatedJson, null,
                "Execution error: " + e.getMessage());
        }
    }

    /**
     * Just translate — no execution (for preview).
     */
    public String translate(String question) {
        String schemaContext = buildSchemaContext();
        return callLlm(question, schemaContext);
    }

    private String buildSchemaContext() {
        var types = typeRepo.findAll();
        if (types.isEmpty()) {
            return "No resource types registered in Schema Registry.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("The following Resource Types are registered in the Heirloom Schema Registry:\n\n");
        for (ResourceType type : types) {
            sb.append("- Type: ").append(type.getName())
              .append(" (domain: ").append(type.getDomain() != null ? type.getDomain() : "default")
              .append(")\n");
            sb.append("  Fields:\n");
            if (type.getFields() != null) {
                for (var field : type.getFields()) {
                    sb.append("    - ").append(field.name())
                      .append(" (").append(field.type())
                      .append(")\n");
                }
            }
            sb.append("  Abilities: ").append(type.getAbilities()).append("\n");
            sb.append("  States: ");
            if (type.getStateMachine() != null) {
                for (var st : type.getStateMachine()) {
                    sb.append(st.from()).append("→").append(st.to()).append(" ");
                }
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String callLlm(String question, String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY not set, NLQ unavailable");
            return null;
        }

        String systemPrompt = """
            You are a Heirloom Query Assistant. Your job is to translate natural language questions into Heirloom JSON DSL queries.

            Heirloom JSON DSL format:
            {
              "type": "TypeName",
              "fields": ["field1", "field2"],
              "filter": { "$eq": { "field": "value" } },
              "sort": { "field": "fieldName", "direction": "asc|desc" },
              "limit": 20,
              "offset": 0
            }

            Supported filter operators: $eq, $neq, $gt, $gte, $lt, $lte, $in, $like, $and, $or

            Rules:
            - Return ONLY the JSON object, no explanation, no markdown
            - Use the correct type name and field names from the schema context
            - If the question asks about something the schema doesn't have, return {"error": "This question cannot be answered with available data"}
            - For aggregation queries, add an "aggregate" block: { "function": "count|sum|avg|min|max", "field": "fieldName", "group_by": ["field1"] }
            """;

        try {
            var messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content",
                    "Schema context:\n" + schemaContext + "\n\nQuestion: " + question)
            );

            var requestBody = Map.of(
                "model", MODEL,
                "messages", messages,
                "temperature", 0.1,
                "max_tokens", 500
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = rest.exchange(
                API_URL, HttpMethod.POST,
                new HttpEntity<>(toJson(requestBody), headers),
                String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("OpenAI API returned {}", response.getStatusCode());
                return null;
            }

            JsonNode root = mapper.readTree(response.getBody());
            String content = root.get("choices").get(0).get("message").get("content").asText();

            // Clean up potential markdown code blocks
            content = content.trim();
            if (content.startsWith("```json")) {
                content = content.substring(7);
            } else if (content.startsWith("```")) {
                content = content.substring(3);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            return content.trim();

        } catch (Exception e) {
            log.error("OpenAI chat API call failed: {}", e.getMessage());
            return null;
        }
    }

    private QueryRequest buildQueryRequest(Map<String, Object> queryMap, QueryMode mode) {
        String type = (String) queryMap.get("type");
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) queryMap.get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) queryMap.get("filter");
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregate = (Map<String, Object>) queryMap.get("aggregate");

        var payload = new QueryPayload(type, filter, fields);
        return new QueryRequest(mode, payload, null, null, null, null);
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    // ─── Result DTO ───────────────────────────────────────────────────────

    public record NLQResult(
        boolean success,
        String generatedQuery,
        QueryResult queryResult,
        String error
    ) {}
}
