from datetime import date
from pathlib import Path

from ml.service.pipeline import predict, train_models, validate_workbook
from ml.service.schemas import ForecastRequest


SAMPLE = Path(__file__).resolve().parents[2] / "hawker_datasets" / "hawker_sales.xlsx"


def test_hawker_workbook_validates():
    result = validate_workbook(SAMPLE)
    assert result.valid
    assert result.date_start.isoformat() == "2026-03-01"
    assert result.date_end.isoformat() == "2026-08-31"
    assert result.row_count == 1734
    assert result.sheets == ["Daily Summary", "Daily Item Sales"]


def test_training_produces_tomorrow_forecast(tmp_path):
    bundle = train_models([SAMPLE], "test-run", tmp_path)
    result = predict(bundle, ForecastRequest(date=date(2026, 9, 27), weather="Cloudy", holiday=False, stall_open=True))
    assert result.predicted_revenue > 0
    assert result.predicted_orders > 0
    assert result.lower_bound <= result.predicted_revenue <= result.upper_bound
    assert result.item_forecasts


def test_closed_day_forecast_is_zero(tmp_path):
    bundle = train_models([SAMPLE], "closed-run", tmp_path)
    result = predict(bundle, ForecastRequest(date=date(2026, 9, 29), weather="Unknown", holiday=False, stall_open=False))
    assert result.predicted_revenue == 0
    assert result.predicted_orders == 0
    assert result.item_forecasts == []
