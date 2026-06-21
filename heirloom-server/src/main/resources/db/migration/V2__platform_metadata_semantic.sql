-- ============================================================================
-- Heirloom Phase 0 — Complete Database Migration
-- Extends existing resource_types + creates 11 new tables
-- ============================================================================

-- 1. Extend existing resource_types table (backward compatible)
ALTER TABLE resource_types
  ADD COLUMN IF NOT EXISTS fully_qualified_name VARCHAR(512),
  ADD COLUMN IF NOT EXISTS domain VARCHAR(128) DEFAULT 'default',
  ADD COLUMN IF NOT EXISTS change_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;

UPDATE resource_types
  SET fully_qualified_name = COALESCE(domain, 'default') || '.' || name
  WHERE fully_qualified_name IS NULL;

ALTER TABLE resource_types
  ADD CONSTRAINT uq_resource_types_fqn UNIQUE (fully_qualified_name);

-- ============================================================================
-- 2. Platform layer tables
-- ============================================================================

-- 2a. discovery_sources — configured data sources for automated discovery
CREATE TABLE IF NOT EXISTS discovery_sources (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_type VARCHAR(64) NOT NULL,
  environment VARCHAR(64) DEFAULT 'prod',
  connection_config JSONB,
  schedule VARCHAR(64) DEFAULT 'manual',
  status VARCHAR(32) DEFAULT 'ACTIVE',
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2b. discovery_reports — scan results
CREATE TABLE IF NOT EXISTS discovery_reports (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256),
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_fqn VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL,
  tables_scanned INTEGER DEFAULT 0,
  tables_failed INTEGER DEFAULT 0,
  metadata_created INTEGER DEFAULT 0,
  proposals_generated INTEGER DEFAULT 0,
  proposals_registered INTEGER DEFAULT 0,
  content_hash VARCHAR(64),
  duration_ms BIGINT,
  error_summary JSONB,
  partial_success BOOLEAN DEFAULT FALSE,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2c. event_log — immutable audit log (append-only)
CREATE TABLE IF NOT EXISTS event_log (
  id BIGSERIAL PRIMARY KEY,
  entity_type VARCHAR(64) NOT NULL,
  entity_id BIGINT,
  entity_fqn VARCHAR(512),
  event_type VARCHAR(32) NOT NULL,
  actor VARCHAR(256),
  entity_version BIGINT,
  change_hash VARCHAR(64),
  denied_reason TEXT,
  denied_operation VARCHAR(64),
  timestamp TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_log_entity ON event_log(entity_type, entity_fqn, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_event_log_actor  ON event_log(actor, timestamp DESC);

-- ============================================================================
-- 3. Metadata layer tables
-- ============================================================================

-- 3a. database_services — database connection definitions
CREATE TABLE IF NOT EXISTS database_services (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  service_type VARCHAR(64) NOT NULL,
  connection_config JSONB,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3b. metadata_databases — database instances
CREATE TABLE IF NOT EXISTS metadata_databases (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  service_fqn VARCHAR(512) NOT NULL,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3c. metadata_schemas — schema namespaces
CREATE TABLE IF NOT EXISTS metadata_schemas (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  database_fqn VARCHAR(512) NOT NULL,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3d. metadata_tables — table/view metadata (columns stored as JSONB)
CREATE TABLE IF NOT EXISTS metadata_tables (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  database_service_fqn VARCHAR(512),
  database_fqn VARCHAR(512),
  database_schema_fqn VARCHAR(512),
  table_type VARCHAR(32) DEFAULT 'BASE TABLE',
  columns JSONB NOT NULL DEFAULT '[]',
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3e. metadata_lineage — data lineage edges
CREATE TABLE IF NOT EXISTS metadata_lineage (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(512),
  fully_qualified_name VARCHAR(1024) NOT NULL UNIQUE,
  from_entity_fqn VARCHAR(512) NOT NULL,
  to_entity_fqn VARCHAR(512) NOT NULL,
  lineage_type VARCHAR(32) NOT NULL,
  column_mappings JSONB DEFAULT '[]',
  source VARCHAR(128),
  description TEXT,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lineage_from ON metadata_lineage(from_entity_fqn);
CREATE INDEX IF NOT EXISTS idx_lineage_to   ON metadata_lineage(to_entity_fqn);

-- ============================================================================
-- 4. Semantic layer tables
-- ============================================================================

-- 4a. proposals — generalized change proposals
CREATE TABLE IF NOT EXISTS proposals (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(512),
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  target_entity_type VARCHAR(64) NOT NULL,
  target_entity_fqn VARCHAR(512),
  proposed_changes JSONB NOT NULL DEFAULT '{}',
  change_type VARCHAR(16) NOT NULL,
  status VARCHAR(32) DEFAULT 'PENDING',
  source VARCHAR(64) DEFAULT 'manual',
  proposed_by VARCHAR(256),
  reviewed_by VARCHAR(256),
  rejection_reason TEXT,
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 4b. mapping_rules — field → Column FQN mappings
CREATE TABLE IF NOT EXISTS mapping_rules (
  id BIGSERIAL PRIMARY KEY,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  type_fqn VARCHAR(512) NOT NULL,
  field_name VARCHAR(256) NOT NULL,
  column_fqn VARCHAR(512) NOT NULL,
  mapping_source VARCHAR(64) DEFAULT 'discovery',
  description TEXT,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mapping_type  ON mapping_rules(type_fqn);
CREATE INDEX IF NOT EXISTS idx_mapping_field ON mapping_rules(type_fqn, field_name);
