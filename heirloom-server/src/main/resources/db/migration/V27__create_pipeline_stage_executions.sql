CREATE TABLE pipeline_stage_executions (
  input_event_id UUID NOT NULL,
  stage_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  output_event_id UUID,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (input_event_id, stage_name)
);
