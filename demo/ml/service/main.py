from __future__ import annotations

from pathlib import Path
import os

from fastapi import FastAPI, HTTPException

from .pipeline import configured_model_root, load_active, predict, train_models, validate_workbook
from .schemas import *


app = FastAPI(title="Sales Forecast ML Service", version="0.1.0")
model_root = configured_model_root()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/datasets/validate", response_model=ValidationResponse)
def validate_dataset(request: ValidationRequest) -> ValidationResponse:
    if not request.path.exists():
        raise HTTPException(status_code=404, detail="Workbook does not exist")
    return validate_workbook(request.path)


@app.post("/internal/models/train", response_model=TrainingResponse)
def train(request: TrainingRequest) -> TrainingResponse:
    if not request.paths:
        raise HTTPException(status_code=400, detail="At least one source is required")
    try:
        bundle = train_models(request.paths, str(request.run_id), model_root)
        return TrainingResponse(
            selected_model=bundle.model_name,
            mae=round(bundle.mae, 3),
            wape=round(bundle.wape, 3),
            artifact_path=bundle.artifact_path,
            message=f"Selected {bundle.model_name} using chronological validation",
        )
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception


def _ensure_active():
    bundle = load_active(model_root)
    if bundle is not None:
        return bundle
    sample = Path(os.getenv("BOOTSTRAP_WORKBOOK", "hawker_datasets/hawker_sales.xlsx")).resolve()
    if not sample.exists():
        raise HTTPException(status_code=409, detail="No trained model is active")
    return train_models([sample], "bootstrap", model_root)


@app.post("/internal/models/predict", response_model=ForecastResponse)
def forecast(request: ForecastRequest) -> ForecastResponse:
    return predict(_ensure_active(), request)
