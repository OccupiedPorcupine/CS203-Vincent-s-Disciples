# Supabase setup and source ingestion

This guide covers running the demo against Supabase and how other applications (PDF and receipt ingestors) write sources into the same database. The Docker stack and the `local` H2 profile keep working without any of this.

## What lives where

| Concern | Without Supabase | With Supabase |
|---|---|---|
| Metadata: sources, training runs, lineage, models, forecasts | Postgres container (Docker) or H2 file (`local` profile) | Supabase Postgres |
| Schema changes | Flyway migrations in `backend/src/main/resources/db/migration/` | Same migrations, run by the backend on startup |
| Raw source files | `STORAGE_PATH/uploads` | Private bucket (`raw-sources`), plus a local working copy |
| Trained model files | `.local-data/models` | Unchanged: still local |

The database stores paths, object keys, and SHA-256 hashes, never file contents. The ML service reads workbooks by file path, so the backend always keeps a working copy in `STORAGE_PATH/uploads`. If that copy is missing when training starts, the backend downloads the file from the bucket and checks it against the recorded hash before using it.

## One-time project setup

1. **Create the Supabase project.** Note the project ref and region.
2. **Create a private storage bucket** named `raw-sources`: Storage → New bucket, with *Public bucket* left off.
3. **Create S3 access keys** under Project Settings → Storage → S3 connection. Note the endpoint, region, access key ID, and secret.
4. **Fill in `demo/.env`** from the Supabase block in `.env.example`. `.env` is gitignored; never commit credentials.
5. **Run the backend once with the owner credentials.** Leave `DATABASE_*` pointing at the `postgres` user for this first run. Flyway applies V1–V4 and creates the `forecast_backend` and `source_ingestor` roles.
6. **Give the roles logins** in the SQL editor, using generated passwords:

   ```sql
   ALTER ROLE forecast_backend WITH LOGIN PASSWORD '<generated>';
   ALTER ROLE source_ingestor WITH LOGIN PASSWORD '<generated>';  -- once the ingestion app exists
   ```

7. **Switch the app to the limited role.** Set `DATABASE_USERNAME=forecast_backend.<project-ref>` and keep `FLYWAY_*` on the `postgres` owner.

## Environment variables

| Variable | Purpose | Supabase value |
|---|---|---|
| `DATABASE_URL` | App connection | Transaction pooler, port 6543: `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | App role | `forecast_backend.<project-ref>` |
| `FLYWAY_URL` | Migration connection. Falls back to `DATABASE_URL` | Session pooler or direct connection, port 5432 |
| `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` | Schema owner. Falls back to `DATABASE_*` | `postgres.<project-ref>` |
| `STORAGE_BACKEND` | `local` (default) or `s3` | `s3` |
| `STORAGE_S3_ENDPOINT` | S3 endpoint | `https://<project-ref>.storage.supabase.co/storage/v1/s3` |
| `STORAGE_S3_REGION` | Bucket region | Project region, for example `ap-southeast-1` |
| `STORAGE_S3_BUCKET` | Bucket name | `raw-sources` |
| `STORAGE_S3_ACCESS_KEY_ID` / `STORAGE_S3_SECRET_ACCESS_KEY` | S3 credentials | From step 3 |

Why there are two connections:

- The transaction pooler cannot hold server-side prepared statements, hence `prepareThreshold=0` in the app URL.
- Migrations need a session connection and the owner role, because they run DDL and create roles.

## Running

```bash
cd demo
docker compose -f compose.yaml -f compose.supabase.yaml up --build backend ml-service frontend
```

`compose.supabase.yaml` passes the Supabase variables from `.env` to the backend and removes its dependency on the Postgres container. Outside Docker, export the same variables and start Spring Boot normally, without the `local` profile.

## Access model

Everything is enforced in Postgres, so it holds no matter which client connects.

| Role | Can do | Cannot do |
|---|---|---|
| `postgres`, the owner | Everything. Owns the tables and bypasses RLS. Used only by Flyway | n/a |
| `forecast_backend` | Read and write all application tables | Change the schema |
| `source_ingestor` | Read `data_sources`; insert rows that are `PENDING_REVIEW`, `included = false`, and have a `storage_key` | Update or delete rows, approve sources, include them in training, touch models or forecasts |
| `anon`, `authenticated` (Supabase Data API) | Nothing. All grants revoked, including default grants on future tables | Everything |

Row-level security is enabled on every table, including `flyway_schema_history`. That table is locked by Flyway while migrations run, so it is secured by the `afterMigrate.sql` callback rather than by a migration.

## Contract for other ingestion apps

PDF, receipt, and other ingestors do their own parsing. They share only this contract with the database:

1. **Hash the raw file** with SHA-256, as lowercase hex.
2. **Upload it** to the `raw-sources` bucket at `sources/<sha256>/<file name>`. Replace characters outside `A-Z a-z 0-9 . _ -` with `_`.
3. **Insert one `data_sources` row** as `source_ingestor`:

   ```sql
   INSERT INTO data_sources (
     id, original_file_name, sha256, status, included, row_count, created_at,
     source_type, mime_type, ingested_by, storage_bucket, storage_key
   ) VALUES (
     gen_random_uuid(), 'receipt-2026-09-27.pdf', '<sha256>', 'PENDING_REVIEW', false, 0, now(),
     'pdf', 'application/pdf', 'receipt-ingestor', 'raw-sources',
     'sources/<sha256>/receipt-2026-09-27.pdf'
   )
   ON CONFLICT (sha256) DO NOTHING;
   ```

Rules the database enforces:

- `source_type` is one of `xlsx`, `pdf`, `receipt_image`, `api`.
- `status` is one of `VALIDATED`, `REJECTED`, `PENDING_REVIEW`. Ingestors may only insert `PENDING_REVIEW`.
- Every row needs a `stored_path` or a `storage_key`. Ingestors must provide `storage_key`, and `storage_bucket` must be set together with it.
- `sha256` is unique, so uploading the same file twice is a no-op.
- `ingested_by` must name the ingesting app. `demo-backend` is reserved.

Training only reads sources that are `VALIDATED`, `included`, and of type `xlsx`. Anything an ingestor submits stays out of model training until it has been reviewed.

## Adding tables later

Supabase exposes the `public` schema through its Data API, so each migration that creates a table must also:

```sql
ALTER TABLE new_table ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON new_table TO forecast_backend;
CREATE POLICY backend_all ON new_table FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
-- plus source_ingestor grants and policies if ingestors write to it
```

V4 already revokes the default `anon` and `authenticated` grants on future tables. Do not reference `flyway_schema_history` inside a migration: Flyway holds a lock on it and the migration will hang.

## Troubleshooting

- **`prepared statement "S_1" already exists`**: the app URL points at port 6543 without `prepareThreshold=0`.
- **`Schema validation: missing table`** on startup: Flyway did not run. Check the `FLYWAY_*` variables and the startup log for `Migrating schema`.
- **`Source is stored in bucket X but storage is configured for Y`**: `STORAGE_S3_BUCKET` differs from the bucket recorded on the source row.
- **`Downloaded file does not match the recorded hash`**: the object in the bucket was replaced or corrupted. Re-upload the original file.
- **Uploads fail with checksum or signature errors**: confirm the endpoint ends in `/storage/v1/s3` and the region matches the project.
