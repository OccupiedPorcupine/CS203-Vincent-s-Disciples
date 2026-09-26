# Chicken rice demo

A self-contained three-service demo for registering Excel sales data and estimating tomorrow's revenue and orders.

## Services

- `frontend/`: React and TypeScript dashboard.
- `backend/`: Spring Boot API, PostgreSQL metadata, and upload storage.
- `ml/service/`: FastAPI validation, feature engineering, model training, and prediction.

The repository's separate model experiments remain in the root-level `../ml/` directory.

## Run the complete application

```bash
cd demo
docker compose up --build
```

Open `http://localhost:8000`. The Spring API runs at `http://localhost:8080` and the internal ML service runs at `http://localhost:8001`.

The first forecast bootstraps a model from `hawker_datasets/hawker_sales.xlsx`. Uploaded files and trained model artifacts are written to the ignored `.local-data/` directory. PostgreSQL records sources, training runs, active models, and forecast history.

For development without Docker, start the ML service and run Spring Boot with the `local` profile. That profile uses an ignored file-backed H2 database while preserving the same entities and APIs.

## Run checks

```bash
cd frontend && npm test
cd .. && python -m pytest ml/tests -q
cd backend && mvn test
```

Spring Boot owns the public API. The frontend does not call Python directly. The Python service remains internal and preserves the pandas, scikit-learn, and XGBoost workflow.
