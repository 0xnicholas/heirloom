package com.heirloom.knowledge.service;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = EmbeddingProvider.class, ignored = NoopEmbeddingProvider.class)
public class NoopEmbeddingProvider implements EmbeddingProvider {
    @Override public float[] embed(String t) { return new float[0]; }
    @Override public List<float[]> embedBatch(List<String> texts) { return texts.stream().map(t -> new float[0]).toList(); }
    @Override public int dimension() { return 0; }
    @Override public boolean isAvailable() { return false; }
}
