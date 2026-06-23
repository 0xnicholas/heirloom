-- V10: Ontology Branching (Phase 4.1)
-- Resource types now carry a branch_name column. NULL = main. Edits to a
-- branch clone the type, set branch_name, and modify. Merging walks all
-- branch_name != null rows and compares against baseHashes + main's
-- current state to surface conflicts.

ALTER TABLE resource_types
    ADD COLUMN branch_name VARCHAR(64);

CREATE INDEX rt_branch_name_idx
    ON resource_types (branch_name)
    WHERE branch_name IS NOT NULL;

COMMENT ON COLUMN resource_types.branch_name IS
    'Phase 4.1 branching: NULL = main; non-null = a branch clone. '
    'Branch queries include main + the branch overlay; main queries '
    'filter branch_name IS NULL.';

CREATE TABLE ontology_branches (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL UNIQUE,
    base_hashes     JSONB        NOT NULL,
    tip_hashes      JSONB,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(128),
    merged_at       TIMESTAMP,
    merged_by       VARCHAR(128),
    description     VARCHAR(512),

    CONSTRAINT ontology_branches_status_chk
        CHECK (status IN ('OPEN', 'MERGED', 'CLOSED'))
);

CREATE INDEX ob_status_idx ON ontology_branches (status);
CREATE INDEX ob_name_idx ON ontology_branches (name);

COMMENT ON TABLE ontology_branches IS
    'Phase 4.1 branching: each branch records the ResourceType changeHash '
    'snapshot at creation (base_hashes) and the current branch overlay '
    '(tip_hashes). Merge uses these to detect conflicts.';