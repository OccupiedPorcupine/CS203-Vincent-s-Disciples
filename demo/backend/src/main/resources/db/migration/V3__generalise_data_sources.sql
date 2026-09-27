-- Sources can now be any raw file (workbooks, PDFs, receipt images, API snapshots)
-- and may be written by ingestion applications other than this backend.
ALTER TABLE data_sources ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'xlsx';
ALTER TABLE data_sources ADD COLUMN mime_type VARCHAR(255);
ALTER TABLE data_sources ADD COLUMN ingested_by VARCHAR(100) NOT NULL DEFAULT 'demo-backend';
ALTER TABLE data_sources ADD COLUMN storage_bucket VARCHAR(255);
ALTER TABLE data_sources ADD COLUMN storage_key VARCHAR(1024);

UPDATE data_sources
SET mime_type = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
WHERE source_type = 'xlsx';

-- External ingestors only have an object-storage key, not a path on this backend's volume.
ALTER TABLE data_sources ALTER COLUMN stored_path DROP NOT NULL;

ALTER TABLE data_sources ADD CONSTRAINT data_sources_source_type_check
  CHECK (source_type IN ('xlsx', 'pdf', 'receipt_image', 'api'));
ALTER TABLE data_sources ADD CONSTRAINT data_sources_status_check
  CHECK (status IN ('VALIDATED', 'REJECTED', 'PENDING_REVIEW'));
ALTER TABLE data_sources ADD CONSTRAINT data_sources_location_check
  CHECK (stored_path IS NOT NULL OR storage_key IS NOT NULL);
ALTER TABLE data_sources ADD CONSTRAINT data_sources_object_check
  CHECK ((storage_bucket IS NULL) = (storage_key IS NULL));
