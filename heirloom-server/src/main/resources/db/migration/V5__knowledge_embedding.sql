-- V5__knowledge_embedding.sql
-- Phase 1: pgvector semantic search support

CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS embed_status VARCHAR(16) DEFAULT 'PENDING';
CREATE INDEX IF NOT EXISTS idx_ka_embedding ON knowledge_articles USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
