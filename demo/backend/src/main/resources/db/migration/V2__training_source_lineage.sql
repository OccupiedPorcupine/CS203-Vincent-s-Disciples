CREATE TABLE training_run_sources (
  id UUID PRIMARY KEY,
  training_run_id UUID NOT NULL REFERENCES training_runs(id),
  data_source_id UUID NOT NULL REFERENCES data_sources(id),
  source_sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT training_run_sources_unique UNIQUE (training_run_id, data_source_id)
);

CREATE INDEX training_run_sources_run_idx ON training_run_sources(training_run_id);
CREATE INDEX training_run_sources_source_idx ON training_run_sources(data_source_id);
