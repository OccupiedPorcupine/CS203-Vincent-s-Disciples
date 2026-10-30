-- Result of the latest import of an uploaded file into daily_sales (issue #41).
-- All null until the file is first imported.
ALTER TABLE training_data_sources ADD COLUMN imported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE training_data_sources ADD COLUMN import_rows_added INTEGER;
ALTER TABLE training_data_sources ADD COLUMN import_rows_overwritten INTEGER;
ALTER TABLE training_data_sources ADD COLUMN import_rows_skipped INTEGER;
