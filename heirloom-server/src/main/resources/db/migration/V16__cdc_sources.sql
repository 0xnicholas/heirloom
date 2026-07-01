-- V16: CDC sources and offset store for PostgreSQL logical replication.
-- Spec: docs/superpowers/specs/2026-07-01-cdc-incremental-sync.md §4

CREATE TABLE cdc_sources (
    id               BIGSERIAL    PRIMARY KEY,
    name             VARCHAR(128) NOT NULL UNIQUE,
    pg_host          VARCHAR(256) NOT NULL,
    pg_port          INTEGER      NOT NULL DEFAULT 5432,
    pg_database      VARCHAR(128) NOT NULL,
    pg_schema        VARCHAR(128) NOT NULL DEFAULT 'public',
    pg_username      VARCHAR(128) NOT NULL,
    pg_password      VARCHAR(256) NOT NULL,
    publication_name VARCHAR(128) NOT NULL,
    slot_name        VARCHAR(128) NOT NULL,
    watched_tables   JSONB        NOT NULL DEFAULT '{}',
    status           VARCHAR(32)  NOT NULL DEFAULT 'STOPPED',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE cdc_offsets (
    source_name      VARCHAR(128) NOT NULL,
    lsn              VARCHAR(64)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_name)
);
