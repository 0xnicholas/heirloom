CREATE TABLE pipeline_run_stages (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  stage_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  next_retry_at TIMESTAMPTZ,
  last_error TEXT,
  UNIQUE (run_uuid, stage_name)
);

CREATE INDEX idx_stages_retry
  ON pipeline_run_stages (status, next_retry_at)
  WHERE status = 'RETRYING';
