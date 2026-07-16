package com.heirloom.core.pipeline;

@FunctionalInterface
public interface PipelineStage {
    PipelineEvent apply(PipelineEvent input, PipelineContext context)
        throws PipelineFailure;
}