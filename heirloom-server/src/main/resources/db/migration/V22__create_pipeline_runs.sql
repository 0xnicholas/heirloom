CREATE TABLE pipeline_runs (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL UNIQUE,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_fqn VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL,
  correlation_id UUID NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  table_fqns TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_pipeline_runs_active
  ON pipeline_runs (tenant_id, source_fqn)
  WHERE status IN ('PENDING','RUNNING','RETRYING');

CREATE INDEX idx_pipeline_runs_status ON pipeline_runs (status);
