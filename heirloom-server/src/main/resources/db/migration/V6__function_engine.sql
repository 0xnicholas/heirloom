-- V6: Function engine additions
-- Adds execution-timeout and audit-toggle columns to security_functions
-- plus a FUNCTION_INVOKED event type hint in changeHash (no schema change needed —
-- the enum lives in code, changeHash is a free-text field).

ALTER TABLE security_functions
    ADD COLUMN timeout_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE security_functions
    ADD COLUMN audit_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN security_functions.timeout_ms IS
    'Per-call execution timeout in milliseconds. 0 = executor default (5s).';

COMMENT ON COLUMN security_functions.audit_enabled IS
    'When true, every invocation appends a FUNCTION_INVOKED row to event_log.';