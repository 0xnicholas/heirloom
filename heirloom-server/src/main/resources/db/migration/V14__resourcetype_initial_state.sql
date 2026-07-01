-- V14: Add initial_state column to resource_types.
-- Required when a ResourceType declares a non-empty state machine.
-- Spec: docs/superpowers/specs/2026-07-01-core-kernel-hardening.md §2.3
-- TypeValidator enforces: state machine non-empty → initialState must be declared
--   and must be a valid state in the state machine.

ALTER TABLE resource_types ADD COLUMN initial_state VARCHAR(64);
