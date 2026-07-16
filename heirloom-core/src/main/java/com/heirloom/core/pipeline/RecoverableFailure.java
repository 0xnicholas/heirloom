package com.heirloom.core.pipeline;

public final class RecoverableFailure extends PipelineFailure {
    public RecoverableFailure(String message) { super(message); }
    public RecoverableFailure(String message, Throwable cause) { super(message, cause); }
}