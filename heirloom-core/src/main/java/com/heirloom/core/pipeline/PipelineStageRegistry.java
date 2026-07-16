package com.heirloom.core.pipeline;

import java.util.Optional;

public interface PipelineStageRegistry {
    void register(PipelineEventType type, PipelineStage stage);
    Optional<PipelineStage> find(PipelineEventType type);
}