ALTER TABLE dish_aliases ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON dish_aliases FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
