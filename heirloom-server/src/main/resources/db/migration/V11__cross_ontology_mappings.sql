-- V11: Cross-Ontology RID mappings (Phase 4.1)
-- Foundation for multi-ontology federation: each ontology is a named namespace;
-- mappings link an (ontology, RID) pair to its counterpart in another ontology.

CREATE TABLE ontologies (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL UNIQUE,
    description   VARCHAR(512),
    created_at    TIMESTAMP    NOT NULL,
    created_by    VARCHAR(128)
);

COMMENT ON TABLE ontologies IS
    'Phase 4.1: registered ontologies. Each ontology is a named namespace; '
    'RIDs inside one ontology can be mapped to RIDs in another via '
    'ontology_mappings. The single Heirloom instance acts as the registry '
    'for cross-ontology lookups; actual data sources may live elsewhere.';

CREATE TABLE ontology_mappings (
    id              BIGSERIAL PRIMARY KEY,
    source_ontology VARCHAR(64)  NOT NULL,
    source_rid      VARCHAR(512) NOT NULL,
    target_ontology VARCHAR(64)  NOT NULL,
    target_rid      VARCHAR(512) NOT NULL,
    mapping_type    VARCHAR(32)  NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(128),
    notes           VARCHAR(1024),

    CONSTRAINT ontology_mappings_type_chk
        CHECK (mapping_type IN ('ALIAS', 'EQUIVALENT', 'RELATED', 'DERIVED_FROM')),

    -- Bidirectional uniqueness: (A, ridA) -> (B, ridB) is the same mapping as
    -- (B, ridB) -> (A, ridA) when looked up from the other side. We enforce
    -- directionality by storing one row per direction via the service layer
    -- and rely on this composite uniqueness for the (ontology, rid, type) tuple.
    UNIQUE (source_ontology, source_rid, target_ontology, mapping_type)
);

CREATE INDEX om_source_idx
    ON ontology_mappings (source_ontology, source_rid);

CREATE INDEX om_target_idx
    ON ontology_mappings (target_ontology, target_rid);

COMMENT ON TABLE ontology_mappings IS
    'Phase 4.1: directed RID mapping between two ontologies. mapping_type: '
    'ALIAS (same resource, different id), EQUIVALENT (semantically equal), '
    'RELATED (linked but distinct), DERIVED_FROM (one copied from the other).';

-- Seed: register the default ontology so existing FQNs map to a known namespace.
INSERT INTO ontologies (name, description, created_at, created_by)
VALUES ('default', 'Built-in default ontology for legacy FQNs', NOW(), 'system')
ON CONFLICT (name) DO NOTHING;