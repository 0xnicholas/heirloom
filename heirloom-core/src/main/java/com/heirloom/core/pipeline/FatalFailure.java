package com.heirloom.core.pipeline;

public final class FatalFailure extends PipelineFailure {
    public FatalFailure(String message) { super(message); }
    public FatalFailure(String message, Throwable cause) { super(message, cause); }
}