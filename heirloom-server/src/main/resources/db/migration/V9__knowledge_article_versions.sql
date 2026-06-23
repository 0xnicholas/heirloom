-- V9: Knowledge article version snapshots
-- Phase 4.1: each update to a KnowledgeArticle captures the previous state
-- as a snapshot row, so prior versions remain queryable after subsequent edits.

CREATE TABLE knowledge_article_versions (
    id              BIGSERIAL PRIMARY KEY,
    -- nullable: versions survive soft/hard delete of the parent article
    article_id      BIGINT,
    -- denormalized so we can list versions by FQN even when the article is gone
    article_fqn     VARCHAR(512) NOT NULL,
    version_number  INT          NOT NULL,
    snapshot_at     TIMESTAMP    NOT NULL,
    snapshot_reason VARCHAR(64)  NOT NULL DEFAULT 'update',

    -- Snapshot fields (denormalised for queryability):
    title           VARCHAR(512),
    description     VARCHAR(1024),
    body            TEXT,
    status          VARCHAR(32),
    type            VARCHAR(128),
    domain          VARCHAR(128),
    author          VARCHAR(256),
    owner           VARCHAR(256),
    resource        VARCHAR(2048),
    file_path       VARCHAR(1024),
    file_hash       VARCHAR(64),
    version         BIGINT
);

CREATE INDEX kav_article_fqn_idx
    ON knowledge_article_versions (article_fqn, version_number DESC);

CREATE INDEX kav_article_id_idx
    ON knowledge_article_versions (article_id);

COMMENT ON TABLE knowledge_article_versions IS
    'Phase 4.1: per-update snapshots of KnowledgeArticle. The latest '
    'version is always the live row in knowledge_articles; this table '
    'holds the history.';