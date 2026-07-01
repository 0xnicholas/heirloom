package com.heirloom.security.pipeline;

/**
 * Thrown by a pipeline step when the request should be rejected.
 * Carries the step number, step name, and reason for the denial.
 */
public class PipelineRejection extends RuntimeException {

    private final int step;
    private final String stepName;

    public PipelineRejection(int step, String stepName, String reason) {
        super(reason);
        this.step = step;
        this.stepName = stepName;
    }

    public int getStep() { return step; }
    public String getStepName() { return stepName; }
}
