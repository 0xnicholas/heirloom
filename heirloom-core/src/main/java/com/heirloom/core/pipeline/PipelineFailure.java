package com.heirloom.core.pipeline;

public sealed abstract class PipelineFailure extends RuntimeException
    permits RecoverableFailure, FatalFailure {

    protected PipelineFailure(String message) { super(message); }
    protected PipelineFailure(String message, Throwable cause) { super(message, cause); }
}