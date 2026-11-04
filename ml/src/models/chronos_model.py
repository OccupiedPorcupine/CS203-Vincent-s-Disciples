"""
Chronos-2 forecasts for the ML service's POST /forecast (issue #106). Chronos-2 is a pretrained
model from Amazon that forecasts a series it has never seen, so it works for any dish without
training on our data. The benchmark that chose it is in ml/README.md (#87).

Each forecast is fed the way the benchmark fed it (src/simulation/chronos_forecaster.py): the
dish's daily sales up to its last recorded day, with days without sales left empty, so the weekly
pattern stays seven steps long, and whether each day is a public holiday. Unlike the benchmark it
gets no rainfall, because the app doesn't keep past rainfall.

The weights are copied into the image when it's built (ml/Dockerfile), so the service never
downloads them. PyTorch is only imported when the model loads, so the rest of the API and the
tests run without it.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import numpy as np

from ml.src.simulation.chronos_forecaster import QUANTILE_LEVELS

log = logging.getLogger(__name__)

MODEL_NAME = "chronos-2"
DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[2] / "artifacts" / "chronos-2"
# The benchmark's context: a year of sales is plenty for weekly and yearly patterns.
CONTEXT_DAYS = 365
# A forecast this many days or more after the last recorded sale is refused.
MAX_HORIZON_DAYS = 64


def model_dir() -> Path:
    return Path(os.environ.get("CHRONOS_MODEL_DIR", DEFAULT_MODEL_DIR))


def load_pipeline(path: Path) -> Any:
    import torch
    from chronos import BaseChronosPipeline

    return BaseChronosPipeline.from_pretrained(str(path), device_map="cpu", torch_dtype=torch.float32)


class LoadingModel:
    """Loads the pipeline on a background thread, so the service starts listening straight away
    (Vercel allows about 25 s for that) while the weights load."""

    def __init__(self, loader=None, path: Path | None = None) -> None:
        self._path = path or model_dir()
        self._loader = loader or load_pipeline
        self._loaded = threading.Event()
        self._pipeline: Any = None
        self.error: str | None = None
        self.load_seconds: float | None = None

    def start(self) -> None:
        if not self._path.exists():
            self.error = f"no weights at {self._path}"
            self._loaded.set()
            log.warning("Chronos-2 unavailable: %s", self.error)
            return
        threading.Thread(target=self._load, name="chronos-load", daemon=True).start()

    def _load(self) -> None:
        started = time.monotonic()
        try:
            self._pipeline = self._loader(self._path)
        except Exception as error:  # any failure leaves the service up, with /forecast refusing
            self.error = f"{type(error).__name__}: {error}"
            log.exception("Chronos-2 failed to load")
        self.load_seconds = time.monotonic() - started
        self._loaded.set()

    @property
    def status(self) -> str:
        if not self._loaded.is_set():
            return "loading"
        return "ready" if self._pipeline is not None else "unavailable"

    def wait(self, seconds: float) -> Any:
        """The pipeline, waiting up to `seconds` for it to load; None if it isn't ready by then."""
        self._loaded.wait(seconds)
        return self._pipeline


def model_input(sales: list[tuple[date, float]], forecast_date: date, holidays: set[date]) -> tuple[dict, int]:
    """Chronos-2's input for one dish, and how many days ahead of the series the forecast date is.
    sales: (date, quantity sold) for days with recorded sales; only those before the date count."""
    before = sorted((day, quantity) for day, quantity in sales if day < forecast_date)
    if not before:
        raise ValueError("No sales are recorded before the forecast date.")
    last = before[-1][0]
    horizon = (forecast_date - last).days
    if horizon >= MAX_HORIZON_DAYS:
        raise ValueError(f"The latest sale is {horizon} days before the forecast date; at most "
                         f"{MAX_HORIZON_DAYS - 1} are allowed.")
    start = max(before[0][0], forecast_date - timedelta(days=CONTEXT_DAYS))
    days = [start + timedelta(days=offset) for offset in range((last - start).days + 1)]
    values = np.full(len(days), np.nan)
    for day, quantity in before:
        if day >= start:
            values[(day - start).days] = quantity
    future = [last + timedelta(days=offset) for offset in range(1, horizon + 1)]
    return {
        "target": values,
        "past_covariates": {"holiday": np.array([1.0 if day in holidays else 0.0 for day in days])},
        "future_covariates": {"holiday": np.array([1.0 if day in holidays else 0.0 for day in future])},
    }, horizon


def forecast(pipeline: Any, sales: list[tuple[date, float]], forecast_date: date, holidays: set[date]) -> list[float]:
    """The forecast for the date at each of QUANTILE_LEVELS. Sales can't be negative, and the
    quantiles are sorted in case the model's cross."""
    inputs, horizon = model_input(sales, forecast_date, holidays)
    quantiles, _ = pipeline.predict_quantiles([inputs], prediction_length=horizon, quantile_levels=QUANTILE_LEVELS)
    values = np.asarray(quantiles[0], dtype=float).reshape(horizon, len(QUANTILE_LEVELS))[-1]
    return np.maximum(np.sort(values), 0.0).tolist()
