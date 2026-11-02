-- Uploaded sales files are kept in the database (issue #48). The server's own disk is wiped on
-- every deploy and isn't shared between instances, so a file saved there could be gone by the time
-- it was imported. One row per upload, apart from training_data_sources so listing uploads never
-- loads the files themselves.
CREATE TABLE training_data_files (
  source_id BIGINT PRIMARY KEY CONSTRAINT training_data_files_source_id_fkey REFERENCES training_data_sources(id) ON DELETE CASCADE,
  content BYTEA NOT NULL
);

-- New uploads have no file on disk. Older ones keep their path and are read from it if it's still there.
ALTER TABLE training_data_sources ALTER COLUMN stored_path DROP NOT NULL;
