-- V28__drop_pipeline_outbox.sql
-- Phase 7b: Kafka adapter replaces DB outbox. Outbox table no longer needed.
DROP TABLE IF EXISTS pipeline_outbox CASCADE;
