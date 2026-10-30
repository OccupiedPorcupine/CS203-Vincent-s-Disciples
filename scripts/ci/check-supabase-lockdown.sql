-- Run by CI after the backend has migrated a Supabase Postgres database.
-- Fails if any table in the public schema is readable through Supabase's Data API:
-- every table must have row level security on, and the anon and authenticated roles
-- must hold no privileges on it (see V2__access_control.sql).

DO $$
DECLARE
  tables_without_rls text;
  api_grants text;
BEGIN
  SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
    INTO tables_without_rls
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT c.relrowsecurity;

  SELECT string_agg(grantee || ' ' || privilege_type || ' on ' || table_name, ', ' ORDER BY table_name, grantee)
    INTO api_grants
    FROM information_schema.role_table_grants
   WHERE table_schema = 'public' AND grantee IN ('anon', 'authenticated');

  IF tables_without_rls IS NOT NULL THEN
    RAISE EXCEPTION 'Tables without row level security: %', tables_without_rls;
  END IF;

  IF api_grants IS NOT NULL THEN
    RAISE EXCEPTION 'Supabase API roles still have access: %', api_grants;
  END IF;

  RAISE NOTICE 'All public tables have row level security and no anon/authenticated grants';
END $$;
