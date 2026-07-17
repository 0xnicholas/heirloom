-- V30__add_conditional_abilities.sql
-- Phase 4.2: Conditional Abilities — abilities that are only active
-- under specific state, time, and/or origin conditions.
-- Stored as JSONB array on resource_types table.

ALTER TABLE resource_types
    ADD COLUMN conditional_abilities JSONB NOT NULL DEFAULT '[]';
