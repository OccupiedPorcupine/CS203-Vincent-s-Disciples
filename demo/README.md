# Chicken rice demo

A self-contained three-service demo for registering Excel sales data and estimating tomorrow's revenue and orders.

## Included flows

- Forecast page at `/` with tomorrow's revenue, order estimate, item forecast, editable conditions, and interactive sales bars.
- Sources page at `/sources` with the active datasets and Excel workbook upload.
- Spring Boot API between the browser and the internal Python forecasting service.

## Services

- `frontend/`: React and TypeScript dashboard.
- `backend/`: Spring Boot API, PostgreSQL metadata, and upload storage.
- `ml/service/`: FastAPI validation, feature engineering, model training, and prediction.

The repository's separate model experiments remain in the root-level `../ml/` directory.

## Layout

```text
demo/
├── frontend/          # React UI
├── backend/           # Spring Boot API
├── ml/                # FastAPI forecasting service
├── hawker_datasets/   # Demo workbook
├── docs/              # Supabase setup and ingestion contract
├── compose.yaml       # Complete local stack
├── compose.supabase.yaml  # Override: run against Supabase instead of local Postgres
└── .env.example       # Configuration reference
```

## Run the complete application

```bash
cd demo
docker compose up --build
```

Local endpoints:

- Frontend: `http://localhost:8000`
- Data sources: `http://localhost:8000/sources`
- Spring API: `http://localhost:8080`
- ML service: `http://localhost:8001`

The first forecast bootstraps a model from `hawker_datasets/hawker_sales.xlsx`. Uploaded files and trained model artifacts are written to the ignored `.local-data/` directory. PostgreSQL records sources, training runs, active models, and forecast history.

## Database and source lineage

- The Docker stack uses PostgreSQL 17 with schema changes managed by Flyway migrations in `backend/src/main/resources/db/migration/`.
- Local development uses a file-backed H2 database under `.local-data/backend/` so the demo starts without installing PostgreSQL.
- Workbooks and trained model files remain in file/object storage; the database stores their paths, hashes, validation metadata, and model relationships rather than large binary blobs.
- `training_run_sources` records the exact data-source IDs and SHA-256 hashes used for every model run. The Sources page labels a workbook **Used by active model** only when that lineage points to the active model version.

For a production system, keep PostgreSQL as the system of record and replace local file storage with managed object storage while retaining the same database references and hashes.

## Running against Supabase

Supabase replaces the Docker Postgres container and, optionally, the local upload folder. Configuration is environment-only, through a gitignored `demo/.env`.

**Start with [docs/supabase.md](docs/supabase.md).** It has a step-by-step dashboard walkthrough, the `.env` template, how to run with or without Docker, how to verify the connection, the access model, the contract for other ingestion apps, troubleshooting, and open decisions. The summary below is for readers who already know the setup.

- **Database.** Flyway runs as the schema owner (`FLYWAY_*`, session pooler or direct connection on port 5432). The app can run as the limited `forecast_backend` role through the transaction pooler (`DATABASE_*`, port 6543 with `prepareThreshold=0`). Without `FLYWAY_*`, Flyway reuses the app credentials.
- **Storage.** `STORAGE_BACKEND=s3` publishes each upload to a private bucket under `sources/<sha256>/<file name>` and records `storage_bucket`/`storage_key`. The backend keeps a working copy in `STORAGE_PATH/uploads` for the ML service and re-downloads it (verifying the SHA-256) if it goes missing.
- **Access control.** `V4__access_control.sql` enables row-level security on every table, strips Supabase's `anon`/`authenticated` grants, and creates two `NOLOGIN` roles: `forecast_backend` (full access to app tables) and `source_ingestor` (may read `data_sources` and insert rows only as `PENDING_REVIEW`, not included, with a storage key). `afterMigrate.sql` applies the same protection to `flyway_schema_history`. New tables need RLS, grants, and a `forecast_backend` policy in their migration.
- **Other ingestion apps** (PDFs, receipts) upload the raw file to the same bucket, then insert a `data_sources` row with their own `ingested_by`, the matching `source_type`, and status `PENDING_REVIEW`. Training only uses validated `xlsx` sources.

```bash
docker compose -f compose.yaml -f compose.supabase.yaml up --build backend ml-service frontend
```

To run the backend against Supabase without Docker, see *Running the app → Option B* in the guide.

For development without Docker, start the ML service and run Spring Boot with the `local` profile. That profile uses an ignored file-backed H2 database while preserving the same entities and APIs.

## Run checks

```bash
cd frontend && npm test
cd .. && python -m pytest ml/tests -q
cd backend && mvn test
```

Spring Boot owns the public API. The frontend does not call Python directly. The Python service remains internal and preserves the pandas, scikit-learn, and XGBoost workflow.
