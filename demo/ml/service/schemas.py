from __future__ import annotations

from datetime import date
from pathlib import Path
from uuid import UUID

from pydantic import BaseModel, Field


class ValidationRequest(BaseModel):
    path: Path


class ValidationResponse(BaseModel):
    valid: bool
    date_start: date | None = None
    date_end: date | None = None
    row_count: int = 0
    sheets: list[str] = Field(default_factory=list)
    errors: list[str] = Field(default_factory=list)


class TrainingRequest(BaseModel):
    run_id: UUID
    paths: list[Path]


class TrainingResponse(BaseModel):
    selected_model: str
    mae: float
    wape: float
    artifact_path: str
    message: str


class ForecastRequest(BaseModel):
    date: date
    weather: str = "Cloudy"
    holiday: bool = False
    stall_open: bool = True


class ItemForecast(BaseModel):
    item: str
    quantity: int
    revenue: float


class ForecastResponse(BaseModel):
    date: date
    predicted_revenue: float
    predicted_orders: int
    lower_bound: float
    upper_bound: float
    recent_average: float
    model_name: str
    training_cutoff: date
    item_forecasts: list[ItemForecast]
