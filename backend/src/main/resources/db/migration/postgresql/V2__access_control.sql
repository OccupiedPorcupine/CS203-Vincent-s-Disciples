-- Postgres/Supabase only. Supabase publishes the public schema through its Data API as the
-- anon and authenticated roles. This backend is the only client, so lock those roles out.
-- The table owner (the postgres user the backend connects as) bypasses RLS and is unaffected.
--
-- Every later migration that creates a table must also ENABLE ROW LEVEL SECURITY on it.
-- flyway_schema_history is locked by Flyway during migrations; afterMigrate.sql secures it.

ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE dishes ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public_holidays ENABLE ROW LEVEL SECURITY;

-- The roles only exist on Supabase, so plain Postgres skips this block.
DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON app_users, dishes, daily_sales, public_holidays FROM %I', api_role);
      -- Also stop Supabase granting them access to tables created by future migrations.
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
