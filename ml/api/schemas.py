from datetime import date

from pydantic import BaseModel, Field


class SalesRecord(BaseModel):
    date: date
    demand: float = Field(ge=0)


class WeatherInput(BaseModel):
    wind: float
    cloud_cover: float
    precipitation: float
    sunshine: float
    air_temperature: float


class PredictionRequest(BaseModel):
    dish: str
    forecast_date: date
    sales_history: list[SalesRecord]
    weather: WeatherInput
    is_holiday: bool


class PredictionResponse(BaseModel):
    dish: str
    forecast_date: date
    predicted_demand: float


class ForecastRequest(BaseModel):
    forecast_date: date
    # Days with recorded sales only; days without are treated as unknown, not as zero.
    sales_history: list[SalesRecord]
    # Public holidays among the history's days and the days up to the forecast date.
    holidays: list[date] = []


class ForecastResponse(BaseModel):
    forecast_date: date
    model: str
    quantile_levels: list[float]
    # The forecast at each of quantile_levels, in the same order, never negative.
    quantiles: list[float]
    median: float


class ModelInfoResponse(BaseModel):
    # None when the artifacts don't record which dishes the model was trained on.
    dishes: list[str] | None


class HealthResponse(BaseModel):
    status: str
    # Chronos-2 behind /forecast: "loading", "ready" or "unavailable".
    forecast_model: str