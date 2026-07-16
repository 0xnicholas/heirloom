package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultPipelineStageRegistry implements PipelineStageRegistry {

    private final ConcurrentHashMap<PipelineEventType, PipelineStage> stages = new ConcurrentHashMap<>();

    @Override
    public void register(PipelineEventType type, PipelineStage stage) {
        stages.put(type, stage);
    }

    @Override
    public Optional<PipelineStage> find(PipelineEventType type) {
        return Optional.ofNullable(stages.get(type));
    }
}