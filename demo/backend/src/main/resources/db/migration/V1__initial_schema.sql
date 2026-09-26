CREATE TABLE data_sources (
  id UUID PRIMARY KEY,
  original_file_name VARCHAR(255) NOT NULL,
  stored_path VARCHAR(1024) NOT NULL,
  sha256 VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  included BOOLEAN NOT NULL DEFAULT TRUE,
  date_start DATE,
  date_end DATE,
  row_count INTEGER NOT NULL DEFAULT 0,
  sheet_names VARCHAR(1000),
  validation_message VARCHAR(2000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE training_runs (
  id UUID PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  selected_model VARCHAR(100),
  mae DOUBLE PRECISION,
  wape DOUBLE PRECISION,
  source_count INTEGER NOT NULL DEFAULT 0,
  message VARCHAR(2000),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE model_versions (
  id UUID PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  artifact_path VARCHAR(1024) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  training_run_id UUID NOT NULL REFERENCES training_runs(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE forecasts (
  id UUID PRIMARY KEY,
  target_date DATE NOT NULL,
  weather VARCHAR(32) NOT NULL,
  holiday BOOLEAN NOT NULL,
  stall_open BOOLEAN NOT NULL,
  predicted_revenue DOUBLE PRECISION NOT NULL,
  predicted_orders INTEGER NOT NULL,
  lower_bound DOUBLE PRECISION NOT NULL,
  upper_bound DOUBLE PRECISION NOT NULL,
  model_version_id UUID REFERENCES model_versions(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX data_sources_created_at_idx ON data_sources(created_at DESC);
CREATE INDEX training_runs_started_at_idx ON training_runs(started_at DESC);
CREATE INDEX model_versions_active_idx ON model_versions(active);
CREATE INDEX forecasts_target_date_idx ON forecasts(target_date DESC);
