# CSD workspace

The repository keeps experimentation, research, and the standalone product demo separate.

## Structure

- `demo/` — self-contained Chicken rice demo application.
- `ml/` — model experiments, notebooks, source data, and evaluation outputs for the eventual system.
- `notebooks/` — data exploration and reporting work.
- `research/` — collected research datasets.
- `database/` — reserved for the final system's database work.

## Documentation

| Topic | Where |
|---|---|
| Running the demo locally or with Docker | [demo/README.md](demo/README.md) |
| Connecting to Supabase (database and file storage), access roles, ingestion contract, open decisions | [demo/docs/supabase.md](demo/docs/supabase.md) |
| Database schema history | [demo/backend/src/main/resources/db/migration/](demo/backend/src/main/resources/db/migration/) |
| Model experiments | [ml/README.md](ml/README.md) |
