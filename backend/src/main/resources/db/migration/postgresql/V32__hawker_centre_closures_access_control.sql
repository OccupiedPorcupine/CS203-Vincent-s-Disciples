-- Same lock-down as the other tables: the backend reads and writes these directly, and Supabase's
-- Data API roles get nothing.
ALTER TABLE hawker_centres ENABLE ROW LEVEL SECURITY;
ALTER TABLE hawker_centre_closures ENABLE ROW LEVEL SECURITY;
ALTER TABLE stall_hawker_centres ENABLE ROW LEVEL SECURITY;
ALTER TABLE nea_open_days ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
  api_role text;
  locked_table text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      FOREACH locked_table IN ARRAY ARRAY['hawker_centres', 'hawker_centre_closures', 'stall_hawker_centres', 'nea_open_days'] LOOP
        EXECUTE format('REVOKE ALL ON %I FROM %I', locked_table, api_role);
      END LOOP;
    END IF;
  END LOOP;
END $$;
