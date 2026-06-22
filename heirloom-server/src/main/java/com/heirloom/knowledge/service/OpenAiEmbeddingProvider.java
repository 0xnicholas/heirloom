package com.heirloom.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
@ConditionalOnProperty(name = "heirloom.knowledge.embedding.provider", havingValue = "openai")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);
    private static final String API_URL = "https://api.openai.com/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small";
    private static final int DIM = 1536;
    private static final int MAX_BATCH = 50;

    private final RestTemplate rest;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean available = true;

    public OpenAiEmbeddingProvider() {
        this.rest = new RestTemplate();
        this.apiKey = System.getenv("OPENAI_API_KEY");
    }

    @Override
    public float[] embed(String text) {
        var result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY not set, embedding unavailable");
            available = false;
            return texts.stream().map(t -> new float[0]).toList();
        }

        List<float[]> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH, texts.size()));
            try {
                results.addAll(callApi(batch));
            } catch (Exception e) {
                log.error("OpenAI embedding API failed: {}", e.getMessage());
                available = false;
                for (int j = 0; j < batch.size(); j++) results.add(new float[0]);
            }
        }
        return results;
    }

    private List<float[]> callApi(List<String> texts) {
        Map<String, Object> body = Map.of(
            "model", MODEL,
            "input", texts
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<String> response = rest.exchange(
            API_URL, HttpMethod.POST, new HttpEntity<>(toJson(body), headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("OpenAI API returned " + response.getStatusCode());
        }

        try {
            JsonNode root = mapper.readTree(response.getBody());
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : root.get("data")) {
                JsonNode emb = item.get("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
                embeddings.add(vec);
            }
            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse embedding response", e);
        }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    @Override public int dimension() { return DIM; }
    @Override public boolean isAvailable() { return available && apiKey != null && !apiKey.isBlank(); }
}
