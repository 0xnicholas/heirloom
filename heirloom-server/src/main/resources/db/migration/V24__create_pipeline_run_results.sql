CREATE TABLE pipeline_run_results (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  stage_name VARCHAR(64) NOT NULL,
  result_type VARCHAR(64) NOT NULL,
  result JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (run_uuid, stage_name)
);
