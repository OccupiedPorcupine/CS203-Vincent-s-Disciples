-- Row-level security and application roles for hosted Postgres (Supabase).
-- Runs unchanged on plain Postgres: Supabase-only roles are handled conditionally.
-- The migration owner bypasses RLS, so Flyway and the Docker stack are unaffected.
-- Every new table needs ENABLE ROW LEVEL SECURITY, grants, and a backend policy.
-- flyway_schema_history is locked by Flyway while migrations run; afterMigrate.sql secures it.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forecast_backend') THEN
    CREATE ROLE forecast_backend NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'source_ingestor') THEN
    CREATE ROLE source_ingestor NOLOGIN;
  END IF;
END $$;

ALTER TABLE data_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_run_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE forecasts ENABLE ROW LEVEL SECURITY;

-- Spring backend: full access to application tables.
GRANT USAGE ON SCHEMA public TO forecast_backend, source_ingestor;
GRANT SELECT, INSERT, UPDATE, DELETE
  ON data_sources, training_runs, training_run_sources, model_versions, forecasts
  TO forecast_backend;
CREATE POLICY backend_all ON data_sources FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
CREATE POLICY backend_all ON training_runs FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
CREATE POLICY backend_all ON training_run_sources FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
CREATE POLICY backend_all ON model_versions FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
CREATE POLICY backend_all ON forecasts FOR ALL TO forecast_backend USING (true) WITH CHECK (true);

-- External ingestion apps: may read sources (to check hashes) and submit new ones for review,
-- but cannot approve them, include them in training, or touch models and forecasts.
GRANT SELECT, INSERT ON data_sources TO source_ingestor;
CREATE POLICY ingestor_read ON data_sources FOR SELECT TO source_ingestor USING (true);
CREATE POLICY ingestor_submit ON data_sources FOR INSERT TO source_ingestor
  WITH CHECK (
    status = 'PENDING_REVIEW'
    AND included = false
    AND ingested_by <> 'demo-backend'
    AND storage_key IS NOT NULL
  );

-- Supabase exposes the public schema through its Data API as anon/authenticated.
-- Nothing here is meant for browsers, so remove those grants, including future defaults.
DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON data_sources, training_runs, training_run_sources, model_versions, forecasts FROM %I', api_role);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
