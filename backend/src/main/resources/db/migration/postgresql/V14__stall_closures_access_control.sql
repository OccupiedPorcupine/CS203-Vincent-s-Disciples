ALTER TABLE rest_days ENABLE ROW LEVEL SECURITY;
ALTER TABLE closed_dates ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON rest_days FROM %I', api_role);
      EXECUTE format('REVOKE ALL ON closed_dates FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
