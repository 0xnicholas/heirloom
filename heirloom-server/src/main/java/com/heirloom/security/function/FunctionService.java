package com.heirloom.security.function;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogRepository;
import com.heirloom.repository.FunctionRepository;
import com.heirloom.security.domain.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Orchestrates Function invocation: look-up by FQN, dispatch to the
 * configured {@link FunctionExecutor}, and emit audit events when
 * {@link Function#getAuditEnabled()} is true.
 */
@Service
public class FunctionService {

    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);

    private final FunctionRepository repository;
    private final FunctionExecutor executor;
    private final EventLogRepository eventLog;

    public FunctionService(FunctionRepository repository,
                           FunctionExecutor executor,
                           EventLogRepository eventLog) {
        this.repository = repository;
        this.executor = executor;
        this.eventLog = eventLog;
    }

    public Object invoke(String functionName, Map<String, Object> inputs, String caller) {
        Function function = repository.findByName(functionName)
                .filter(f -> !Boolean.TRUE.equals(f.getDeleted()))
                .orElseThrow(() -> new FunctionExecutionException(functionName,
                        "Function not found: " + functionName));

        long startMs = System.currentTimeMillis();
        Object result;
        try {
            result = executor.execute(function, inputs);
        } catch (FunctionExecutionException e) {
            // Audit failures too when audit is enabled — a denied invocation is
            // a stronger signal than a successful one for security review.
            if (Boolean.TRUE.equals(function.getAuditEnabled())) {
                emitAudit(function, caller, null, System.currentTimeMillis() - startMs,
                        "FAILED: " + e.getMessage());
            }
            throw e;
        }

        if (Boolean.TRUE.equals(function.getAuditEnabled())) {
            emitAudit(function, caller, summarise(result), System.currentTimeMillis() - startMs, null);
        }
        return result;
    }

    private void emitAudit(Function function, String caller, String resultSummary,
                           long durationMs, String error) {
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.FUNCTION_INVOKED);
        event.setActor(caller != null ? caller : "unknown");
        event.setEntityType("function");
        event.setEntityFQN(function.getFullyQualifiedName());
        event.setEntityId(function.getId());
        event.setChangeHash(error != null
                ? "FAILED in " + durationMs + "ms — " + error
                : "OK in " + durationMs + "ms — result: " + resultSummary);
        eventLog.append(event);
        log.info("Function audit: fqn={} caller={} duration={}ms status={}",
                function.getFullyQualifiedName(), caller, durationMs,
                error != null ? "FAILED" : "OK");
    }

    /** Avoid logging huge result payloads in the audit row. */
    private static String summarise(Object result) {
        if (result == null) return "null";
        String s = result.toString();
        return s.length() > 200 ? s.substring(0, 197) + "..." : s;
    }
}