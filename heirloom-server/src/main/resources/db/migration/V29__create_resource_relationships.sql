-- V29__create_resource_relationships.sql
-- Phase 2.5: Graph Store — instance-level resource relationships
-- Three semantics: OWNERSHIP (cascade delete), REFERENCE (break link),
-- ASSOCIATION (remove edge)
-- Spec: ADR-006 (Three Relationship Semantics)

CREATE TABLE resource_relationships (
    id              BIGSERIAL    PRIMARY KEY,
    source_rid      VARCHAR(256) NOT NULL REFERENCES heirloom_resources(rid) ON DELETE CASCADE,
    target_rid      VARCHAR(256) NOT NULL REFERENCES heirloom_resources(rid) ON DELETE CASCADE,
    relationship_type VARCHAR(64) NOT NULL,  -- label from ResourceType Relationship definition
    semantics       VARCHAR(32)  NOT NULL CHECK (semantics IN ('OWNERSHIP', 'REFERENCE', 'ASSOCIATION')),
    attributes      JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(128),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Prevent duplicate edges
    CONSTRAINT uq_resource_relationship UNIQUE (source_rid, target_rid, relationship_type)
);

CREATE INDEX idx_rel_source     ON resource_relationships(source_rid) WHERE NOT deleted;
CREATE INDEX idx_rel_target     ON resource_relationships(target_rid) WHERE NOT deleted;
CREATE INDEX idx_rel_semantics  ON resource_relationships(semantics) WHERE NOT deleted;
CREATE INDEX idx_rel_type       ON resource_relationships(relationship_type) WHERE NOT deleted;
