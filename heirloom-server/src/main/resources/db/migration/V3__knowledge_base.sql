-- V3__knowledge_base.sql
-- Heirloom Phase 0.5a — Knowledge Base Foundation

CREATE TABLE IF NOT EXISTS knowledge_sources (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_type VARCHAR(64) NOT NULL,
  path VARCHAR(1024) NOT NULL,
  branch VARCHAR(256) DEFAULT 'main',
  config JSONB NOT NULL DEFAULT '{}',
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

CREATE TABLE IF NOT EXISTS knowledge_articles (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  file_path VARCHAR(1024) NOT NULL,
  file_hash VARCHAR(64) NOT NULL,
  source_fqn VARCHAR(512) NOT NULL,
  type VARCHAR(128) NOT NULL,
  domain VARCHAR(128) DEFAULT 'default',
  title VARCHAR(512),
  description VARCHAR(1024),
  resource VARCHAR(2048),
  body TEXT,
  frontmatter_raw TEXT,
  frontmatter JSONB NOT NULL DEFAULT '{}',
  tags JSONB NOT NULL DEFAULT '[]',
  references_jsonb JSONB NOT NULL DEFAULT '[]',
  citations_jsonb JSONB NOT NULL DEFAULT '[]',
  author VARCHAR(256),
  owner VARCHAR(256),
  okf_version VARCHAR(16) DEFAULT '0.1',
  status VARCHAR(32) DEFAULT 'published',
  sync_status VARCHAR(32) DEFAULT 'OK',
  sync_error TEXT,
  last_synced_at TIMESTAMPTZ,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ka_file_path ON knowledge_articles(source_fqn, file_path);
CREATE INDEX IF NOT EXISTS idx_ka_source_fqn ON knowledge_articles(source_fqn, deleted);
