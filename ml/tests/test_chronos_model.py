from datetime import date, timedelta

import numpy as np
import pytest
from fastapi.testclient import TestClient

from ml.src.models import chronos_model
from ml.src.models.chronos_model import QUANTILE_LEVELS, LoadingModel, forecast, model_input

# These tests use a stand-in for Chronos-2, so they need neither PyTorch nor the weights.

MONDAY = date(2026, 1, 5)


class FakePipeline:
    """Returns quantiles 1, 2, ... for every step (the last step 100 more), and records its inputs."""

    def __init__(self, crossed=False):
        self.calls = []
        self.crossed = crossed

    def predict_quantiles(self, inputs, prediction_length, quantile_levels):
        self.calls.append((inputs, prediction_length))
        steps = np.tile(np.arange(1, len(quantile_levels) + 1, dtype=float), (prediction_length, 1))
        steps[-1] += 100
        if self.crossed:
            steps[-1] = steps[-1][::-1] - 110
        return [steps.reshape(1, prediction_length, -1)], None


def test_the_series_runs_to_the_last_sale_with_days_without_sales_empty():
    sales = [(MONDAY, 10.0), (MONDAY + timedelta(days=2), 12.0), (MONDAY + timedelta(days=3), 13.0)]
    inputs, horizon = model_input(sales, MONDAY + timedelta(days=6), {MONDAY + timedelta(days=2), MONDAY + timedelta(days=5)})

    np.testing.assert_array_equal(inputs["target"], [10, np.nan, 12, 13])
    assert horizon == 3  # Friday, Saturday, Sunday
    assert inputs["past_covariates"]["holiday"].tolist() == [0, 0, 1, 0]
    assert inputs["future_covariates"]["holiday"].tolist() == [0, 1, 0]


def test_only_sales_before_the_date_and_within_a_year_count():
    sales = [(MONDAY - timedelta(days=offset), 5.0) for offset in range(1, 500)] + [(MONDAY, 99.0)]
    inputs, horizon = model_input(sales, MONDAY, set())

    assert len(inputs["target"]) == chronos_model.CONTEXT_DAYS
    assert horizon == 1
    assert 99 not in inputs["target"]


def test_no_sales_or_a_long_gap_is_refused():
    with pytest.raises(ValueError):
        model_input([], MONDAY, set())
    with pytest.raises(ValueError):
        model_input([(MONDAY, 1.0)], MONDAY + timedelta(days=chronos_model.MAX_HORIZON_DAYS), set())


def test_the_forecast_is_the_last_steps_quantiles_sorted_and_not_negative():
    pipeline = FakePipeline()
    values = forecast(pipeline, [(MONDAY, 10.0)], MONDAY + timedelta(days=3), set())

    assert pipeline.calls[0][1] == 3
    assert values == [101.0 + index for index in range(len(QUANTILE_LEVELS))]

    crossed = forecast(FakePipeline(crossed=True), [(MONDAY, 10.0)], MONDAY + timedelta(days=1), set())
    assert crossed == sorted(crossed) and min(crossed) == 0


def test_missing_weights_leave_the_model_unavailable(tmp_path):
    model = LoadingModel(path=tmp_path / "missing")
    model.start()

    assert model.status == "unavailable"
    assert model.wait(0) is None


def test_the_model_loads_in_the_background():
    loaded = []
    model = LoadingModel(loader=lambda path: loaded.append(path) or FakePipeline(), path=chronos_model.Path("."))
    model.start()

    assert model.wait(5) is not None
    assert model.status == "ready"


def forecast_client(monkeypatch, pipeline):
    model = LoadingModel(loader=lambda path: pipeline, path=chronos_model.Path("."))
    monkeypatch.setattr(chronos_model, "LoadingModel", lambda: model)
    from ml.api.main import app
    return TestClient(app)


def test_the_forecast_endpoint_returns_quantiles(monkeypatch):
    payload = {
        "forecast_date": "2026-01-08",
        "sales_history": [{"date": "2026-01-05", "demand": 10}, {"date": "2026-01-06", "demand": 12}],
        "holidays": ["2026-01-08"],
    }
    pipeline = FakePipeline()
    with forecast_client(monkeypatch, pipeline) as client:
        health = client.get("/health").json()
        response = client.post("/forecast", json=payload)

    assert health["forecast_model"] in ("loading", "ready")
    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "chronos-2"
    assert body["quantile_levels"] == QUANTILE_LEVELS
    assert body["median"] == pytest.approx(101 + QUANTILE_LEVELS.index(0.5))
    inputs, horizon = pipeline.calls[0]
    assert horizon == 2
    assert inputs[0]["future_covariates"]["holiday"].tolist() == [0, 1]


def test_the_forecast_endpoint_says_when_the_model_is_unavailable(monkeypatch, tmp_path):
    model = LoadingModel(path=tmp_path / "missing")
    monkeypatch.setattr(chronos_model, "LoadingModel", lambda: model)
    from ml.api.main import app

    with TestClient(app) as client:
        response = client.post("/forecast", json={"forecast_date": "2026-01-08", "sales_history": []})

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_UNAVAILABLE"
