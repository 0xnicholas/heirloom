-- V4__knowledge_fts.sql
-- Phase 1: Full-text search support for knowledge articles

-- Weighted tsvector: title(A) > description(B) > body(C)
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS search_vector tsvector 
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title,'')), 'A') ||
    setweight(to_tsvector('english', coalesce(description,'')), 'B') ||
    setweight(to_tsvector('english', coalesce(body,'')), 'C')
  ) STORED;

CREATE INDEX IF NOT EXISTS idx_ka_search ON knowledge_articles USING GIN (search_vector);
