package com.heirloom.security.condition;

import com.heirloom.domain.Resource;
import com.heirloom.schema.domain.Ability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Evaluates conditional abilities at runtime.
 * Used by ActionPipeline.stepGate to check if a conditional ability
 * is currently valid based on state, time, and context conditions.
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    /**
     * Evaluate whether a conditional ability is currently granted,
     * given the resource state and the current execution context.
     */
    public EvaluationResult evaluate(ConditionalAbility condition,
                                      Resource currentResource,
                                      String targetState,
                                      ExecutionContext ctx) {
        // 1. State condition
        if (!evaluateStateCondition(condition, currentResource, targetState)) {
            return EvaluationResult.denied("state",
                "required state: " + condition.stateFrom()
                + (condition.stateTo() != null ? " → " + condition.stateTo() : "")
                + ", current: " + currentResource.getCurrentState());
        }

        // 2. Time condition
        if (!evaluateTimeCondition(condition)) {
            return EvaluationResult.denied("time",
                "time window: " + condition.timeFrom() + "-" + condition.timeTo());
        }

        // 3. Origin/context condition
        if (!evaluateOriginCondition(condition, ctx)) {
            return EvaluationResult.denied("origin",
                "allowed origins: " + condition.allowedOrigins()
                + ", current: " + ctx.origin());
        }

        return EvaluationResult.GRANTED;
    }

    private boolean evaluateStateCondition(ConditionalAbility condition,
                                            Resource resource,
                                            String targetState) {
        String stateFrom = condition.stateFrom();
        String stateTo = condition.stateTo();
        String currentState = resource.getCurrentState();

        // No state condition → always passes
        if (stateFrom == null && stateTo == null) return true;

        // From → To transition condition
        if (stateFrom != null && stateTo != null) {
            boolean fromOk = stateFrom.equals(currentState);
            boolean toOk = stateTo.equals(targetState);
            return fromOk && toOk;
        }

        // From only: ability only in this state
        if (stateFrom != null) {
            return stateFrom.equals(currentState);
        }

        // To only: ability only when transitioning to this state
        if (stateTo != null) {
            return stateTo.equals(targetState);
        }

        return true;
    }

    private boolean evaluateTimeCondition(ConditionalAbility condition) {
        String timeFrom = condition.timeFrom();
        String timeTo = condition.timeTo();

        // No time condition → always passes
        if (timeFrom == null && timeTo == null) return true;

        LocalTime now = LocalTime.now();
        try {
            if (timeFrom != null && timeTo != null) {
                LocalTime from = LocalTime.parse(timeFrom);
                LocalTime to = LocalTime.parse(timeTo);
                // Handle overnight ranges (e.g. 22:00-06:00)
                if (to.isBefore(from)) {
                    return !now.isBefore(from) || !now.isAfter(to);
                }
                return !now.isBefore(from) && !now.isAfter(to);
            }
            if (timeFrom != null) {
                return !now.isBefore(LocalTime.parse(timeFrom));
            }
            if (timeTo != null) {
                return !now.isAfter(LocalTime.parse(timeTo));
            }
        } catch (Exception e) {
            log.warn("Invalid time condition: {}-{}", timeFrom, timeTo);
            return false;
        }
        return true;
    }

    private boolean evaluateOriginCondition(ConditionalAbility condition,
                                             ExecutionContext ctx) {
        String allowedOrigins = condition.allowedOrigins();
        if (allowedOrigins == null || allowedOrigins.isBlank()) return true;

        String origin = ctx.origin();
        if (origin == null || origin.isBlank()) return false;

        String[] allowed = allowedOrigins.split(",");
        for (String a : allowed) {
            if (a.trim().equalsIgnoreCase(origin.trim())) return true;
        }
        return false;
    }

    /**
     * Filter a list of abilities to only those that are currently granted.
     */
    public List<Ability> filterGranted(List<ConditionalAbility> conditions,
                                        Resource resource,
                                        String targetState,
                                        ExecutionContext ctx) {
        return conditions.stream()
            .filter(c -> evaluate(c, resource, targetState, ctx).granted())
            .map(ConditionalAbility::ability)
            .toList();
    }

    // ─── DTOs ───────────────────────────────────────────────────────────────

    public record EvaluationResult(boolean granted, String reason, String conditionType) {
        static final EvaluationResult GRANTED = new EvaluationResult(true, null, null);
        static EvaluationResult denied(String type, String reason) {
            return new EvaluationResult(false, reason, type);
        }
    }

    /**
     * Runtime execution context — passed from ActionPipeline.
     */
    public record ExecutionContext(
        String actorId,
        String actorRole,
        String origin  // e.g. "workshop", "api", "sdk"
    ) {}
}
