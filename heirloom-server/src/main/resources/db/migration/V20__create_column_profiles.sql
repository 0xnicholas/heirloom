CREATE TABLE IF NOT EXISTS column_profiles (
    id BIGSERIAL PRIMARY KEY,
    table_fqn VARCHAR(512) NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    profiled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    null_count BIGINT,
    null_rate DOUBLE PRECISION,
    distinct_count BIGINT,
    distinct_rate DOUBLE PRECISION,
    empty_string_count BIGINT,
    min_value TEXT,
    max_value TEXT,
    avg_length DOUBLE PRECISION,
    top_values JSONB,
    detected_class VARCHAR(32),
    quality_score DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_column_profiles_table
    ON column_profiles(table_fqn, column_name, profiled_at DESC);
