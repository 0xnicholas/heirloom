-- V13: Heirloom Resource Store.
-- Resource instances with semantic metadata (RID, type, owner, state, version).
-- Business fields stored as JSONB. GIN index for field-level filtering.
-- Spec: docs/superpowers/specs/2026-07-01-core-kernel-hardening.md §2.2

CREATE TABLE heirloom_resources (
    id              BIGSERIAL    PRIMARY KEY,
    rid             VARCHAR(256) NOT NULL UNIQUE,
    resource_type   VARCHAR(128) NOT NULL REFERENCES resource_types(name),
    owner           VARCHAR(256),
    current_state   VARCHAR(64)  NOT NULL,
    fields          JSONB        NOT NULL DEFAULT '{}',
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_resources_rid   ON heirloom_resources(rid);
CREATE INDEX idx_resources_type  ON heirloom_resources(resource_type);
CREATE INDEX idx_resources_owner ON heirloom_resources(owner);
CREATE INDEX idx_resources_state ON heirloom_resources(resource_type, current_state);
CREATE INDEX idx_resources_fields_gin ON heirloom_resources USING GIN (fields jsonb_path_ops);
