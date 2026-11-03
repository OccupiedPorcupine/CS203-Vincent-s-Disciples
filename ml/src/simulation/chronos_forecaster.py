"""
Pretrained forecasting models as backtest forecasters (issue #87): Amazon's Chronos-2 and
Chronos-Bolt, which forecast series they were never trained on.

They need PyTorch and chronos-forecasting (ml/requirements-chronos.txt), which the app and CI
don't install, so `chronos` is only imported when a model is loaded.

Each dish's recorded sales become a daily series running up to the day before the forecast,
with closed days left empty (NaN), so the weekly pattern stays seven steps long. Chronos-2 also
gets the day's rainfall and whether it's a public holiday, for the past and for the day being
forecast; Chronos-Bolt takes no covariates.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from ml.src.simulation.backtest import Forecast, History, Target, adjust_for_sell_outs

# Quantiles asked for; Forecast.quantile interpolates between them. Chronos-Bolt was trained on
# 0.1-0.9 only, and the app's policies use nothing outside that range.
QUANTILE_LEVELS = [round(level, 2) for level in np.arange(0.10, 0.91, 0.05)]

MODELS = {
    "chronos-2": "amazon/chronos-2",
    "chronos-bolt-tiny": "amazon/chronos-bolt-tiny",
}


def load_pipeline(model_id: str) -> Any:
    import torch
    from chronos import BaseChronosPipeline

    return BaseChronosPipeline.from_pretrained(model_id, device_map="cpu", torch_dtype=torch.float32)


class ChronosForecaster:
    def __init__(self, name: str, pipeline: Any, calendar: pd.DataFrame | None = None,
                 context_days: int = 365, use_covariates: bool = True, adjust_sold_out: bool = False,
                 batch_size: int = 256) -> None:
        """calendar: one row per date with `holiday` (name or "") and `rainfall_mm`, covering
        the history and the forecast days. Needed when use_covariates is set."""
        self.name = name
        self.pipeline = pipeline
        self.context_days = context_days
        self.use_covariates = use_covariates
        self.adjust_sold_out = adjust_sold_out
        self.batch_size = batch_size
        if use_covariates:
            dates = calendar["date"].to_numpy().astype("datetime64[D]")
            self._holiday = dict(zip(dates, (calendar["holiday"].fillna("") != "").astype(float)))
            self._rain = dict(zip(dates, calendar["rainfall_mm"].astype(float)))

    def forecast(self, history: History, target: Target) -> Forecast | None:
        return self.forecast_batch([history], [target])[0]

    def forecast_batch(self, histories: list[History], targets: list[Target]) -> list[Forecast | None]:
        inputs, positions = [], []
        for position, (history, target) in enumerate(zip(histories, targets)):
            if len(history.dates) == 0:
                continue
            inputs.append(self.model_input(adjust_for_sell_outs(history) if self.adjust_sold_out else history, target))
            positions.append(position)

        forecasts: list[Forecast | None] = [None] * len(histories)
        if not inputs:
            return forecasts
        # Only Chronos-2's pipeline takes a batch size.
        options = {"batch_size": self.batch_size} if self.use_covariates else {}
        quantiles, _ = self.pipeline.predict_quantiles(inputs, prediction_length=1, quantile_levels=QUANTILE_LEVELS,
                                                       **options)
        for position, values in zip(positions, quantiles):
            forecasts[position] = to_forecast(np.asarray(values, dtype=float).reshape(-1))
        return forecasts

    def model_input(self, history: History, target: Target) -> Any:
        """The daily series from up to context_days before the target to the day before it."""
        end = np.datetime64(target.date, "D")
        start = max(np.datetime64(history.dates[0], "D"), end - np.timedelta64(self.context_days, "D"))
        days = np.arange(start, end, dtype="datetime64[D]")
        values = np.full(len(days), np.nan)
        keep = history.dates.astype("datetime64[D]") >= start
        values[(history.dates.astype("datetime64[D]")[keep] - start).astype(int)] = history.sold[keep]

        if not self.use_covariates:
            import torch
            return torch.tensor(values, dtype=torch.float32)
        return {
            "target": values,
            "past_covariates": {
                "holiday": np.array([self._holiday.get(day, 0.0) for day in days]),
                "rainfall_mm": np.array([self._rain.get(day, 0.0) for day in days]),
            },
            "future_covariates": {
                "holiday": np.array([1.0 if target.holiday else 0.0]),
                "rainfall_mm": np.array([target.rainfall_mm]),
            },
        }


def to_forecast(values: np.ndarray) -> Forecast:
    """values: the forecast at each of QUANTILE_LEVELS. Sales can't be negative, and the
    quantiles are sorted in case the model's cross."""
    values = np.maximum(np.sort(values), 0.0)
    return Forecast(float(np.interp(0.5, QUANTILE_LEVELS, values)),
                    lambda p: float(np.interp(p, QUANTILE_LEVELS, values)))
