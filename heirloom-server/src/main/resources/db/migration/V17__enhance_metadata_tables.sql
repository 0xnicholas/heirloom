ALTER TABLE metadata_tables
  ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS domain_fqn VARCHAR(256),
  ADD COLUMN IF NOT EXISTS constraints JSONB NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(32) NOT NULL DEFAULT 'Created',
  ADD COLUMN IF NOT EXISTS certification JSONB;

CREATE INDEX IF NOT EXISTS idx_tables_domain ON metadata_tables(domain_fqn);
CREATE INDEX IF NOT EXISTS idx_tables_tags ON metadata_tables USING GIN (tags jsonb_path_ops);
