package com.heirloom.knowledge.service;
import java.util.List;

public interface EmbeddingProvider {
    float[] embed(String text);
    default List<float[]> embedBatch(List<String> texts) { return texts.stream().map(this::embed).toList(); }
    int dimension();
    boolean isAvailable();
}
