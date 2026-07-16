CREATE TABLE pipeline_outbox (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  event_type VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  claimed_at TIMESTAMPTZ,
  claimed_by VARCHAR(128),
  claimed_until TIMESTAMPTZ,
  not_before TIMESTAMPTZ,
  dispatched_at TIMESTAMPTZ,
  attempts INT NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending
  ON pipeline_outbox (status, claimed_until, not_before, created_at)
  WHERE status IN ('PENDING','CLAIMED');
