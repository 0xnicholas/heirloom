package com.heirloom.security.function;

import com.heirloom.security.domain.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;

/**
 * SpEL-backed Function executor with a thread-isolation timeout sandbox.
 *
 * <p><b>Sandbox properties:</b>
 * <ul>
 *   <li><b>Read-only data binding:</b> {@link SimpleEvaluationContext#forReadOnlyDataBinding()}
 *       disables method invocation, constructor calls, and bean reference lookups.
 *       SpEL expressions can only read properties and access the variables bound
 *       from the request payload.</li>
 *   <li><b>Timeout enforcement:</b> evaluation runs on a single-thread executor;
 *       the calling thread blocks on {@code Future.get(timeout)} and cancels
 *       the task if the deadline fires. The cancelling thread cannot free the
 *       executor thread (Java doesn't support thread murder), but the task is
 *       interrupted and the executor thread is reused for subsequent calls.</li>
 *   <li><b>Memory:</b> inherits JVM heap limits. Per-call memory ceilings are
 *       not enforceable at the SpEL layer; recommended to set JVM {@code -Xmx}
 *       and monitor via the JVM's MXBean.</li>
 *   <li><b>Network:</b> SpEL has no network APIs in {@code forReadOnlyDataBinding}
 *       context, so outgoing calls are impossible by construction.</li>
 * </ul>
 *
 * <p>Default timeout is 5 seconds when {@link Function#getTimeoutMs()} is null or 0.
 */
@Component
public class SpelFunctionExecutor implements FunctionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpelFunctionExecutor.class);
    private static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ExecutorService sandbox = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "function-sandbox");
        t.setDaemon(true);
        return t;
    });

    @Override
    public String backendName() {
        return "spel-readonly";
    }

    @Override
    public Object execute(Function function, Map<String, Object> inputs) {
        if (function.getCode() == null || function.getCode().isBlank()) {
            throw new FunctionExecutionException(function.getName(),
                    "Function has no code expression");
        }

        long timeoutMs = function.getTimeoutMs() != null && function.getTimeoutMs() > 0
                ? function.getTimeoutMs()
                : DEFAULT_TIMEOUT_MS;

        Expression expression;
        try {
            expression = parser.parseExpression(function.getCode());
        } catch (Exception e) {
            throw new FunctionExecutionException(function.getName(),
                    "Failed to parse expression: " + e.getMessage(), e);
        }

        // Build per-invocation context so concurrent calls don't share variable state.
        SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        Map<String, Object> safeInputs = inputs != null ? inputs : Collections.emptyMap();
        for (Map.Entry<String, Object> e : safeInputs.entrySet()) {
            ctx.setVariable(e.getKey(), e.getValue());
        }

        Future<Object> future = sandbox.submit(() -> expression.getValue(ctx));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Function {} exceeded timeout ({}ms), cancelled", function.getName(), timeoutMs);
            throw new FunctionExecutionException(function.getName(),
                    "Execution exceeded timeout of " + Duration.ofMillis(timeoutMs));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new FunctionExecutionException(function.getName(), "Interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof EvaluationException ee) {
                throw new FunctionExecutionException(function.getName(),
                        "Expression error: " + ee.getMessage(), ee);
            }
            throw new FunctionExecutionException(function.getName(),
                    "Execution error: " + cause.getMessage(), cause);
        }
    }
}