ALTER TABLE chat_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_inbound_messages ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE api_role text;
BEGIN
  FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
      EXECUTE format('REVOKE ALL ON chat_links FROM %I', api_role);
      EXECUTE format('REVOKE ALL ON chat_inbound_messages FROM %I', api_role);
    END IF;
  END LOOP;
END $$;
