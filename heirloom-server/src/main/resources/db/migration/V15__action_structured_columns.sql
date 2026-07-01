-- V15: Action structured columns.
-- Adds type-safe columns alongside deprecated String columns.
-- Spec: docs/superpowers/specs/2026-07-01-core-kernel-hardening.md §3.2

ALTER TABLE security_actions
  ADD COLUMN IF NOT EXISTS target_type_fqn  VARCHAR(256),
  ADD COLUMN IF NOT EXISTS req_ability      VARCHAR(32),
  ADD COLUMN IF NOT EXISTS state_gate_json  JSONB,
  ADD COLUMN IF NOT EXISTS input_schema_json JSONB;

-- Old columns remain for backward compatibility:
--   requiredAbility  (String, deprecated)
--   stateGate        (String, deprecated)
