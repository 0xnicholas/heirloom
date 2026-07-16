CREATE TABLE pipeline_dead_letter (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_fqn VARCHAR(512) NOT NULL,
  stage_name VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  attempts INT NOT NULL,
  last_error TEXT,
  payload JSONB NOT NULL,
  failed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  replayed_at TIMESTAMPTZ,
  replayed_by VARCHAR(255)
);

CREATE INDEX idx_dlq_unreplayed
  ON pipeline_dead_letter (failed_at DESC)
  WHERE replayed_at IS NULL;
