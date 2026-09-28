-- Postgres/Supabase only. Flyway runs this after every migrate, once it has released its
-- history table (a migration cannot alter that table without deadlocking on Flyway's lock).
-- Keeps Flyway's bookkeeping out of Supabase's Data API. Safe to run repeatedly.

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
