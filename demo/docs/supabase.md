# Supabase setup and source ingestion

This guide covers connecting the demo to Supabase, checking that it works, and how other applications (PDF and receipt ingestors) write sources into the same database. It assumes no prior Supabase experience. The Docker stack and the `local` H2 profile keep working without any of this.

Contents:

1. [What lives where](#what-lives-where)
2. [Joining an existing project](#joining-an-existing-project)
3. [Setting up a new Supabase project](#setting-up-a-new-supabase-project)
4. [Filling in `demo/.env`](#filling-in-demoenv)
5. [Running the app](#running-the-app)
6. [Checking that it works](#checking-that-it-works)
7. [Optional: run the app as the limited role](#optional-run-the-app-as-the-limited-role)
8. [Environment variable reference](#environment-variable-reference)
9. [Access model](#access-model)
10. [Contract for other ingestion apps](#contract-for-other-ingestion-apps)
11. [Adding tables later](#adding-tables-later)
12. [Troubleshooting](#troubleshooting)
13. [Status and open decisions](#status-and-open-decisions)

## What lives where

Supabase provides two services this project uses: a Postgres database and S3-compatible file storage.

| Concern | Without Supabase | With Supabase |
|---|---|---|
| Metadata: sources, training runs, lineage, models, forecasts | Postgres container (Docker) or H2 file (`local` profile) | Supabase Postgres |
| Schema changes | Flyway migrations in `backend/src/main/resources/db/migration/` | Same migrations, run by the backend on startup |
| Raw source files | `STORAGE_PATH/uploads` | Private bucket `raw-sources`, plus a local working copy |
| Trained model files | `.local-data/models` | Unchanged: still local |

The database stores paths, object keys, and SHA-256 hashes, never file contents. Each upload goes to `raw-sources` at `sources/<sha256>/<file name>`.

The ML service reads workbooks by file path, so the backend always keeps a working copy in `STORAGE_PATH/uploads`. If that copy is missing when training starts, the backend downloads the file from the bucket and checks it against the recorded hash before using it.

## Joining an existing project

If the team's Supabase project already exists, you do not need to create anything:

1. Ask a project owner to invite you under **Organization settings → Team** in the Supabase dashboard.
2. Get the `demo/.env` values from a teammate through a password manager or another private channel. Never share them through git, chat, or screenshots.
3. Continue from [Running the app](#running-the-app). The tables already exist, and the migrations will report "Schema is up to date".

## Setting up a new Supabase project

Keep a private notes file open. You will collect seven values: project ref, database password, pooler host, S3 endpoint, S3 region, access key ID, and secret access key. Supabase updates its dashboard from time to time, so a label may differ slightly from what is written here.

### 1. Create the account and project

1. Go to [supabase.com](https://supabase.com) and click **Start your project** or **Sign in**. Signing in with GitHub is simplest.
2. If asked to create an **organization**, give it any name, choose **Personal**, pick the **Free** plan, and click **Create organization**.
3. Fill in **Create a new project**:
   - **Project name**: for example `sales-forecast`.
   - **Database password**: click **Generate a password** and copy it straight into your notes; it cannot be viewed again later. Use letters and numbers only. Symbols such as `$ @ # & %` break connection strings and `.env` parsing.
   - **Region**: choose one close to the team.
4. Click **Create new project** and wait one to two minutes while it provisions.
5. Copy the **project ref** from the address bar. For `supabase.com/dashboard/project/abcdefghijklmnop/...`, the ref is `abcdefghijklmnop`.

### 2. Find the pooler host

1. Click **Connect** at the top of the project page. A panel titled *Connect to your project* opens.
2. It opens on the **Framework** tab. Ignore that tab: it is for apps that use Supabase's JavaScript library, and this backend connects to Postgres directly.
3. Click the **Direct** tab ("Connection string").
4. Choose **Session pooler**. Do not use *Direct connection*, which often fails on home networks because it is IPv6-only. If there is a **Type** dropdown, leave it on **URI**.
5. The panel shows a line like:

   ```
   postgresql://postgres.abcdefghijklmnop:[YOUR-PASSWORD]@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres
   ```

   Copy the part between `@` and `:5432`, here `aws-0-ap-northeast-1.pooler.supabase.com`. That is the **pooler host**. It may start with `aws-1-`; copy it exactly as shown.

### 3. Create the storage bucket

1. In the left sidebar, click **Storage**. Hover over the icons to see their names.
2. Click **New bucket**, name it exactly `raw-sources`, and leave **Public bucket** off.
3. Click **Create** or **Save**.

### 4. Create the S3 access keys

1. Open the storage settings: **Project Settings** (the gear icon at the bottom of the sidebar) → **Storage**. Some dashboard versions show a **Settings** or **S3** link inside the Storage page instead.
2. In **S3 Connection**, turn on **Enable connection via S3 protocol** if the toggle is there. Note the **Endpoint** and the **Region**.
   - The endpoint is always `https://<project ref>.storage.supabase.co/storage/v1/s3`.
   - The region is the project's region, for example `ap-northeast-1`.
3. Under **S3 Access Keys**, click **New access key**, enter a description such as `demo backend`, and click **Create**.
4. Copy the **Access key ID** and the **Secret access key** now. The secret is only shown once.

## Filling in `demo/.env`

`demo/.env` is gitignored and stays on your machine. Create it from the example:

```bash
cp demo/.env.example demo/.env
```

Replace its contents with the block below and fill in each `<...>`. Do not put spaces around `=` and do not add quotes.

```
DATABASE_URL=jdbc:postgresql://<pooler host>:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.<project ref>
DATABASE_PASSWORD=<database password>

STORAGE_BACKEND=s3
STORAGE_S3_ENDPOINT=https://<project ref>.storage.supabase.co/storage/v1/s3
STORAGE_S3_REGION=<region>
STORAGE_S3_BUCKET=raw-sources
STORAGE_S3_ACCESS_KEY_ID=<access key id>
STORAGE_S3_SECRET_ACCESS_KEY=<secret access key>
```

Two things commonly go wrong here:

- `DATABASE_URL` must use the `jdbc:postgresql://` form above. Do not paste the `postgresql://...[YOUR-PASSWORD]@...` line from the dashboard; the username and password have their own lines.
- The username really is `postgres.` followed by the project ref.

This first setup uses the `postgres` owner account for both the app and migrations. When the `FLYWAY_*` variables are absent, migrations reuse the `DATABASE_*` credentials. That is enough to run the demo. [Run the app as the limited role](#optional-run-the-app-as-the-limited-role) later for better isolation.

## Running the app

Either option below runs migrations on startup. The first run against a new project logs `Migrating schema "public" to version "4 - access control"`; later runs log `Schema "public" is up to date`.

### Option A: Docker

Start Docker Desktop, then:

```bash
cd demo
docker compose -f compose.yaml -f compose.supabase.yaml up --build backend ml-service frontend
```

`compose.supabase.yaml` reads `demo/.env`, passes the Supabase variables to the backend, and removes its dependency on the local Postgres container. Ports 8000, 8001, and 8080 must be free, so stop any local dev servers first.

### Option B: Without Docker

Start the ML service and frontend as described in the [demo README](../README.md), then run the backend with `.env` loaded:

```bash
cd demo/backend
mvn package -DskipTests
set -a && source ../.env && set +a
ML_BASE_URL=http://127.0.0.1:8001 java -jar target/sales-forecast-backend-0.1.0.jar
```

Do not use the `local` profile here: it switches the backend to the H2 database.

## Checking that it works

1. **Startup log.** Look for `Started SalesForecastApplication` with no `ERROR` lines before it.
2. **Tables.** In the dashboard, open **Table Editor**. You should see `data_sources`, `training_runs`, `training_run_sources`, `model_versions`, `forecasts`, and `flyway_schema_history`, each marked as RLS enabled.
3. **Upload.** Open http://localhost:8000/sources and upload `demo/hawker_datasets/hawker_sales.xlsx`.
4. **Stored file.** In **Storage → raw-sources** you should see `sources/<long hash>/hawker_sales.xlsx`.
5. **Training.** Click **Apply to forecast**. The page reports which model the forecast now uses.

To check the same things in SQL, run these in **SQL Editor → New query**:

```sql
SELECT original_file_name, status, source_type, storage_bucket, storage_key FROM data_sources;
SELECT bucket_id, name, (metadata->>'size')::int AS bytes FROM storage.objects ORDER BY created_at DESC LIMIT 5;
SELECT relname, relrowsecurity FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r';
SELECT count(*) AS lineage_rows FROM training_run_sources;
```

## Optional: run the app as the limited role

Migration V4 creates a `forecast_backend` role that can read and write the app tables but cannot change the schema. To use it:

1. In **SQL Editor → New query**, run the following with a new letters-and-numbers password. The expected result is "Success. No rows returned".

   ```sql
   ALTER ROLE forecast_backend WITH LOGIN PASSWORD '<new password>';
   ```

2. In **Connect → Direct → Transaction pooler**, confirm the host is the same as your pooler host. The port is `6543`.
3. Replace the three `DATABASE_*` lines in `demo/.env`. After this, migrations run as the owner and the app runs as the limited role:

   ```
   DATABASE_URL=jdbc:postgresql://<pooler host>:6543/postgres?sslmode=require&prepareThreshold=0
   DATABASE_USERNAME=forecast_backend.<project ref>
   DATABASE_PASSWORD=<new password>
   FLYWAY_URL=jdbc:postgresql://<pooler host>:5432/postgres?sslmode=require
   FLYWAY_USERNAME=postgres.<project ref>
   FLYWAY_PASSWORD=<database password>
   ```

4. Restart the backend.

The transaction pooler (port 6543) cannot hold server-side prepared statements, hence `prepareThreshold=0`. Migrations need the session pooler (port 5432) and the owner role, because they run DDL and create roles.

Give `source_ingestor` a login the same way once the ingestion app exists.

## Environment variable reference

| Variable | Purpose | Supabase value |
|---|---|---|
| `DATABASE_URL` | App connection | Session pooler (port 5432), or transaction pooler (port 6543) with `prepareThreshold=0` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | App credentials | `postgres.<ref>`, or `forecast_backend.<ref>` for the limited role |
| `FLYWAY_URL` | Migration connection. Defaults to `DATABASE_URL` | Session pooler (port 5432) |
| `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` | Migration credentials; must be the schema owner. Default to `DATABASE_*` | `postgres.<ref>` |
| `STORAGE_BACKEND` | `local` (default) or `s3` | `s3` |
| `STORAGE_S3_ENDPOINT` | S3 endpoint | `https://<ref>.storage.supabase.co/storage/v1/s3` |
| `STORAGE_S3_REGION` | Bucket region | Project region, for example `ap-northeast-1` |
| `STORAGE_S3_BUCKET` | Bucket name | `raw-sources` |
| `STORAGE_S3_ACCESS_KEY_ID` / `STORAGE_S3_SECRET_ACCESS_KEY` | S3 credentials | From **Storage → S3 Access Keys** |
| `ML_BASE_URL` | ML service address (Option B only) | `http://127.0.0.1:8001` |
| `STORAGE_PATH` | Local working-copy folder | Defaults to `../.local-data` |

## Access model

Everything is enforced in Postgres, so it holds whichever client connects.

| Role | Can do | Cannot do |
|---|---|---|
| `postgres` (owner) | Everything. Owns the tables and bypasses RLS. Used by Flyway | n/a |
| `forecast_backend` | Read and write all application tables | Change the schema |
| `source_ingestor` | Read `data_sources`; insert rows that are `PENDING_REVIEW`, `included = false`, and have a `storage_key` | Update or delete rows, approve sources, include them in training, touch models or forecasts |
| `anon`, `authenticated` (Supabase Data API) | Nothing. All grants revoked, including default grants on future tables | Everything |

Row-level security is enabled on every table, including `flyway_schema_history`. That table is locked by Flyway while migrations run, so it is secured by the `afterMigrate.sql` callback rather than by a migration.

The frontend never talks to Supabase directly, so the dashboard's publishable (anon) key is not used anywhere.

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

Supabase exposes the `public` schema through its Data API, so each migration that creates a table must also secure it:

```sql
ALTER TABLE new_table ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON new_table TO forecast_backend;
CREATE POLICY backend_all ON new_table FOR ALL TO forecast_backend USING (true) WITH CHECK (true);
-- plus source_ingestor grants and policies if ingestors write to it
```

V4 already revokes the default `anon`/`authenticated` grants on future tables. Never reference `flyway_schema_history` inside a migration: Flyway holds a lock on it and the migration will hang.

Migrations are append-only. Once a version has run against Supabase, add a new `V<n>__*.sql` file rather than editing it.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `password authentication failed` | Wrong password, or the username is missing the `.<project ref>` suffix |
| `Connection refused` or a timeout | Wrong pooler host or port. Recopy it from **Connect → Direct → Session pooler** |
| `prepared statement "S_1" already exists` | The port 6543 URL is missing `&prepareThreshold=0` |
| `Schema validation: missing table` | Migrations did not run. Check the startup log for `Migrating schema`, and check that the migration credentials are the `postgres.<ref>` owner |
| `ports are not available ... 8080` when starting Docker | A local dev server is already on 8000, 8001, or 8080. Stop it (`lsof -nP -iTCP:8080 -sTCP:LISTEN` shows which process) or use Option B |
| Upload fails with 403, or a checksum or signature error | Wrong S3 endpoint, region, or keys. The endpoint must end in `/storage/v1/s3` |
| `Source is stored in bucket X but storage is configured for Y` | `STORAGE_S3_BUCKET` differs from the bucket recorded on the source row |
| `Downloaded file does not match the recorded hash` | The object in the bucket was replaced or corrupted. Re-upload the original file |
| `!override` or a YAML error when running compose | Docker Compose is older than 2.24. Update Docker Desktop |
| App suddenly cannot connect after a quiet week | Free projects pause after about a week of inactivity. Open the dashboard and click **Restore project** |

## Status and open decisions

As of 2026-09-27:

- The team project is connected and verified end to end: migrations V1–V4 applied, an upload stored in `raw-sources`, and a training run recorded its lineage.
- The app currently connects as the `postgres` owner. Switching to `forecast_backend` is optional and documented above.
- `source_ingestor` has no login yet; add one when the PDF/receipt ingestion app is built.
- Trained model files still live on local disk (`.local-data/models`) and are not yet in object storage.

Open decisions for price validation:

1. What "validation data for price estimation" means: observed prices used to score the model's estimates, or reference prices used as model inputs. This determines the design of the planned `price_observations` table.
2. Whether price workbooks share the sales workbook's sheet layout or need their own validator.
3. Whether the ingestion app writes to Supabase directly as `source_ingestor` or through a Spring API endpoint.
