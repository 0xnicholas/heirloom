package com.heirloom.security.dto;

import java.util.Map;

/**
 * Request body for POST /v1/functions/{name}/invoke.
 *
 * @param inputs map of variable name → value, bound into the SpEL expression
 *               context (e.g. {@code {"amount": 1000, "tier": "gold"}} →
 *               expression {@code amount * 0.1 + (tier == 'gold' ? 50 : 0)})
 */
public record InvokeFunctionRequest(Map<String, Object> inputs) {}