package com.heirloom.core.pipeline;

public enum PipelineStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    RETRYING,
    DEAD_LETTER
}
