-- V7: Role.knowledgeRestrictions JSONB column
-- Phase 2.3 Knowledge Capability — per-Role restrictions on knowledge ops
-- (allowedDomains, allowedTypes, deniedTypes, maxDepth, allowDrafts).

ALTER TABLE security_roles
    ADD COLUMN knowledge_restrictions JSONB;

COMMENT ON COLUMN security_roles.knowledge_restrictions IS
    'Per-Role restrictions on knowledge operations (allowedDomains, allowedTypes, '
    'deniedTypes, maxDepth, allowDrafts). NULL = no restrictions beyond capability.';