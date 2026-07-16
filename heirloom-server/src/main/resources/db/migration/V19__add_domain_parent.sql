ALTER TABLE metadata_domains
  ADD COLUMN IF NOT EXISTS parent_fqn VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_domains_parent ON metadata_domains(parent_fqn);
