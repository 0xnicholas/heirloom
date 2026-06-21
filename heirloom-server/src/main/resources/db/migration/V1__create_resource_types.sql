CREATE TABLE IF NOT EXISTS resource_types (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(1024),
    fields      JSONB NOT NULL DEFAULT '[]'::jsonb,
    abilities   JSONB NOT NULL DEFAULT '[]'::jsonb,
    state_machine JSONB NOT NULL DEFAULT '[]'::jsonb,
    relationships JSONB NOT NULL DEFAULT '[]'::jsonb,
    version     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_resource_types_name ON resource_types (name);
