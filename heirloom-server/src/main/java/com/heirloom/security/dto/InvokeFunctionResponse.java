package com.heirloom.security.dto;

/**
 * Response body for a successful Function invocation. Wraps the result so
 * clients can distinguish "result is null" from "no result returned".
 */
public record InvokeFunctionResponse(String functionName, String outputType, Object result) {}