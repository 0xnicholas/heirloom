package com.heirloom.security.function;

/**
 * Thrown when a {@link com.heirloom.security.domain.Function} cannot be evaluated
 * — either parse error, runtime error inside the expression, or a sandbox violation
 * such as timeout.
 */
public class FunctionExecutionException extends RuntimeException {
    private final String functionName;

    public FunctionExecutionException(String functionName, String message) {
        super(message);
        this.functionName = functionName;
    }

    public FunctionExecutionException(String functionName, String message, Throwable cause) {
        super(message, cause);
        this.functionName = functionName;
    }

    public String getFunctionName() {
        return functionName;
    }
}