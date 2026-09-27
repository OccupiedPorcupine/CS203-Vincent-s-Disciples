-- Runs after every migrate, once Flyway has released its schema history table.
-- Keeps Flyway's bookkeeping out of Supabase's Data API. Safe to repeat.
ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
