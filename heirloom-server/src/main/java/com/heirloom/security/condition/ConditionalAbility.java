package com.heirloom.security.condition;

import com.heirloom.schema.domain.Ability;

/**
 * A conditional ability — an ability that is only granted when
 * specific conditions are met. Used for Phase 4.2 Conditional Abilities.
 *
 * @param ability      the ability being conditionally granted (e.g. DROP)
 * @param stateFrom    if set, ability only applies when resource state matches
 * @param stateTo      if set (with stateFrom), ability applies when transitioning
 *                     from→to; if stateFrom is null, ability only in this state
 * @param timeFrom     ISO time (HH:mm) — earliest time ability may be used
 * @param timeTo       ISO time (HH:mm) — latest time ability may be used
 * @param allowedOrigins comma-separated list of allowed origins (e.g. "workshop,api")
 * @param description   human-readable explanation of the condition
 */
public record ConditionalAbility(
    Ability ability,
    String stateFrom,
    String stateTo,
    String timeFrom,
    String timeTo,
    String allowedOrigins,
    String description
) {}
