-- V8: Resource Type field-level visibility (Phase 1.3 generic Perspective Engine)
-- JSONB map of fieldName -> [roleName, ...]. Missing field entry = visible to everyone.
-- '*' in the list = wildcard grant.

ALTER TABLE resource_types
    ADD COLUMN field_visibility JSONB;

COMMENT ON COLUMN resource_types.field_visibility IS
    'Phase 1.3: per-field visibility config. JSONB shape: '
    '{"fieldName": ["roleName", "*"]}. Missing field = visible to anyone with read.';