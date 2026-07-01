package com.heirloom.security.domain;

import com.heirloom.schema.domain.FieldType;

/**
 * Describes a single input field for an Action.
 *
 * @param fieldName the field name (must match a field on the target ResourceType)
 * @param fieldType the expected type
 * @param required  whether this input must be provided
 */
public record ActionInput(String fieldName, FieldType fieldType, boolean required) {}
