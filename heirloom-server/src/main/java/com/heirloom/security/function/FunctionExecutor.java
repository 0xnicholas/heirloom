package com.heirloom.security.function;

import com.heirloom.security.domain.Function;

import java.util.Map;

/**
 * Executes a {@link Function} in a sandboxed environment.
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Parsing the Function's code expression</li>
 *   <li>Binding the input parameters (variables → expression context)</li>
 *   <li>Applying the timeout (default 5s if {@link Function#getTimeoutMs()} is 0)</li>
 *   <li>Cancelling execution if the timeout fires</li>
 *   <li>Converting the result to a value compatible with {@link Function#getOutputType()}</li>
 * </ul>
 *
 * <p>Sandbox guarantees beyond timeout/memory are backend-specific. See
 * {@link SpelFunctionExecutor} for the restrictions enforced by the SpEL backend.
 */
public interface FunctionExecutor {

    /** Evaluate {@code function} with the given input variables. */
    Object execute(Function function, Map<String, Object> inputs);

    /** Backend identifier used in audit logs and metrics. */
    String backendName();
}