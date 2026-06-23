-- V12: Add JSONB details column to event_log for Phase 3.1 knowledge audit events.
-- See docs/superpowers/specs/2026-06-23-knowledge-audit-events.md §3.1.
-- Intentionally no GIN index — current dashboards do not filter on details.

ALTER TABLE event_log ADD COLUMN details JSONB;