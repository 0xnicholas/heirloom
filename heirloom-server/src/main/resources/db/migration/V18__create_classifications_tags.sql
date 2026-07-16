CREATE TABLE IF NOT EXISTS metadata_classifications (
    id BIGSERIAL PRIMARY KEY,
    fully_qualified_name VARCHAR(256) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    change_hash VARCHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    owner VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS metadata_tags (
    id BIGSERIAL PRIMARY KEY,
    fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
    name VARCHAR(256) NOT NULL,
    classification_fqn VARCHAR(256) NOT NULL,
    parent_fqn VARCHAR(512),
    style JSONB,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    change_hash VARCHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE metadata_tags
  ADD CONSTRAINT IF NOT EXISTS fk_tags_classification
  FOREIGN KEY (classification_fqn)
  REFERENCES metadata_classifications(fully_qualified_name);

CREATE INDEX IF NOT EXISTS idx_tags_classification ON metadata_tags(classification_fqn);
CREATE INDEX IF NOT EXISTS idx_tags_parent ON metadata_tags(parent_fqn);
