from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from ml.src.simulation.backtest import History, Target, forecast_all
from ml.src.simulation.chronos_forecaster import QUANTILE_LEVELS, ChronosForecaster, to_forecast

# These tests use a stand-in for the model, so they need neither PyTorch nor the weights.

START = date(2026, 1, 5)  # a Monday


def calendar():
    days = pd.date_range("2025-12-01", "2026-03-31")
    return pd.DataFrame({"date": days, "holiday": ["Chinese New Year" if day == pd.Timestamp("2026-02-17") else ""
                                                   for day in days],
                         "rainfall_mm": np.arange(len(days), dtype=float)})


def history(days_open):
    dates = np.array([np.datetime64(START + timedelta(days=offset)) for offset in days_open])
    return History(dates, np.arange(len(dates), dtype=float) + 10, np.full(len(dates), np.nan))


class FakePipeline:
    """Returns quantiles 1, 2, ... for each series, and records what it was given."""

    def __init__(self):
        self.calls = []

    def predict_quantiles(self, inputs, prediction_length, quantile_levels, **options):
        self.calls.append((inputs, prediction_length, options))
        values = np.arange(1, len(quantile_levels) + 1, dtype=float)
        return [values.reshape(1, 1, -1) for _ in inputs], None


def test_the_series_runs_daily_to_the_day_before_with_closed_days_empty():
    forecaster = ChronosForecaster("Chronos-2", FakePipeline(), calendar())
    open_days = [0, 2, 3, 5]  # closed on the 2nd and 5th day
    model_input = forecaster.model_input(history(open_days), Target(np.datetime64(START + timedelta(days=7)), "", 4.5))

    target = model_input["target"]
    assert len(target) == 7  # 5 to 11 January
    np.testing.assert_array_equal(target, [10, np.nan, 11, 12, np.nan, 13, np.nan])
    assert len(model_input["past_covariates"]["rainfall_mm"]) == 7
    assert model_input["future_covariates"]["rainfall_mm"].tolist() == [4.5]


def test_the_context_is_cut_to_context_days_and_holidays_are_flagged():
    forecaster = ChronosForecaster("Chronos-2", FakePipeline(), calendar(), context_days=30)
    model_input = forecaster.model_input(history(range(60)), Target(np.datetime64("2026-03-06"), "", 0.0))

    assert len(model_input["target"]) == 30
    flagged = forecaster.model_input(history(range(43)), Target(np.datetime64("2026-02-18"), "", 0.0))
    assert flagged["past_covariates"]["holiday"][-1] == 1.0  # 17 February
    assert flagged["past_covariates"]["holiday"][:-1].sum() == 0


def test_forecasts_come_from_the_quantiles_and_empty_histories_get_none():
    pipeline = FakePipeline()
    forecaster = ChronosForecaster("Chronos-2", pipeline, calendar(), batch_size=64)
    target = Target(np.datetime64(START + timedelta(days=10)), "", 0.0)

    forecasts = forecast_all(forecaster, [history(range(10)), history([]), history(range(8))], [target] * 3)

    assert forecasts[1] is None
    assert len(pipeline.calls) == 1 and len(pipeline.calls[0][0]) == 2  # one batch, without the empty one
    assert pipeline.calls[0][1] == 1 and pipeline.calls[0][2] == {"batch_size": 64}
    median = QUANTILE_LEVELS.index(0.5) + 1
    assert forecasts[0].point == pytest.approx(median)
    assert forecasts[0].quantile(0.1) == pytest.approx(1)
    assert forecasts[2].quantile(0.9) == pytest.approx(len(QUANTILE_LEVELS))


def test_negative_or_crossed_quantiles_are_cleaned_up():
    values = np.linspace(5, -3, len(QUANTILE_LEVELS))  # crossed, partly negative
    forecast = to_forecast(values)
    assert forecast.quantile(0.1) == 0
    assert forecast.quantile(0.9) == pytest.approx(5)
    assert all(forecast.quantile(a) <= forecast.quantile(b) for a, b in zip(QUANTILE_LEVELS, QUANTILE_LEVELS[1:]))
