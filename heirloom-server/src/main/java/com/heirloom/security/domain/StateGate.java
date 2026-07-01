package com.heirloom.security.domain;

/**
 * Structured state gate — replaces the old String {@code "state = Active"}.
 * Declares which state a Resource must be in (fromState) and what state it
 * will be in after the Action executes (toState).
 *
 * @param fromState the state the Resource must currently be in (required)
 * @param toState   the state after execution, or null if the caller decides
 */
public record StateGate(String fromState, String toState) {}
